/*
 * Copyright (c) 2002-2015, the original author or authors.
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * http://www.opensource.org/licenses/bsd-license.php
 */
package org.jline.reader;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.BufferedReader;
import java.io.File;
import java.io.Flushable;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Stack;

import org.jline.Completer;
import org.jline.Console;
import org.jline.Console.Signal;
import org.jline.Console.SignalHandler;
import org.jline.ConsoleReader;
import org.jline.Highlighter;
import org.jline.History;
import org.jline.console.Attributes;
import org.jline.console.Attributes.ControlChar;
import org.jline.console.Size;
import org.jline.reader.history.MemoryHistory;
import org.jline.utils.AnsiHelper;
import org.jline.utils.DiffHelper;
import org.jline.utils.InfoCmp.Capability;
import org.jline.utils.Log;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.Signals;
import org.jline.utils.WCWidth;

import static org.jline.reader.KeyMap.ESCAPE;
import static org.jline.utils.NonBlockingReader.READ_EXPIRED;
import static org.jline.utils.Preconditions.checkNotNull;

/**
 * A reader for console applications. It supports custom tab-completion,
 * saveable command history, and command line editing.
 *
 * @author <a href="mailto:mwp1@cornell.edu">Marc Prud'hommeaux</a>
 * @author <a href="mailto:jason@planet57.com">Jason Dillon</a>
 * @author <a href="mailto:gnodet@gmail.com">Guillaume Nodet</a>
 */
public class ConsoleReaderImpl implements ConsoleReader, Flushable
{
    public static final char NULL_MASK = 0;

    public static final int TAB_WIDTH = 8;

    public static final long COPY_PASTE_DETECTION_TIMEOUT = 50l;
    public static final long BLINK_MATCHING_PAREN_TIMEOUT = 500l;

    /**
     * Possible states in which the current readline operation may be in.
     */
    protected enum State {
        /**
         * The user is just typing away
         */
        NORMAL,
        /**
         * In the middle of a emacs seach
         */
        SEARCH,
        FORWARD_SEARCH,
        /**
         * VI "yank-to" operation ("y" during move mode)
         */
        VI_YANK_TO,
        /**
         * VI "delete-to" operation ("d" during move mode)
         */
        VI_DELETE_TO,
        /**
         * VI "change-to" operation ("c" during move mode)
         */
        VI_CHANGE_TO,
        /**
         * readLine should exit and return the buffer content
         */
        DONE,
        /**
         * readLine should exit and throw an EOFException
         */
        EOF,
        /**
         * readLine should exit and throw an UserInterruptException
         */
        INTERRUPT
    }

    protected enum Messages
    {
        DISPLAY_CANDIDATES,
        DISPLAY_CANDIDATES_YES,
        DISPLAY_CANDIDATES_NO,
        DISPLAY_MORE;

        protected static final
        ResourceBundle
                bundle =
                ResourceBundle.getBundle(CandidateListCompletionHandler.class.getName(), Locale.getDefault());

        public String format(final Object... args) {
            if (bundle == null)
                return "";
            else
                return String.format(bundle.getString(name()), args);
        }
    }

    protected static final int NO_BELL = 0;
    protected static final int AUDIBLE_BELL = 1;
    protected static final int VISIBLE_BELL = 2;


    //
    // Constructor variables
    //

    /** The console to use */
    protected final Console console;
    /** The inputrc url */
    protected final URL inputrc;
    /** The application name, used when parsing the inputrc */
    protected final String appName;
    /** The console keys mapping */
    protected final ConsoleKeys consoleKeys;



    //
    // Configuration
    //
    protected final Map<String, String> variables = new HashMap<>();
    protected History history = new MemoryHistory();
    protected final List<Completer> completers = new LinkedList<>();
    protected CompletionHandler completionHandler = new CandidateListCompletionHandler();
    protected Highlighter highlighter = new DefaultHighlighter();

    //
    // State variables
    //

    protected final CursorBuffer buf = new CursorBuffer();

    protected Size size;

    protected String prompt;
    protected int    promptLen;

    protected Character mask;

    protected CursorBuffer originalBuffer = null;

    protected StringBuffer searchTerm = null;

    protected String previousSearchTerm = "";

    protected int searchIndex = -1;


    // Reading buffers
    protected final StringBuilder opBuffer = new StringBuilder();
    protected final Stack<Character> pushBackChar = new Stack<>();


    /**
     * Last character searched for with a vi character search
     */
    protected char  charSearchChar = 0;           // Character to search for
    protected char  charSearchLastInvokeChar = 0; // Most recent invocation key
    protected char  charSearchFirstInvokeChar = 0;// First character that invoked

    /**
     * The vi yank buffer
     */
    protected String yankBuffer = "";

    protected KillRing killRing = new KillRing();

    protected boolean quotedInsert;

    protected boolean recording;

    protected String macro = "";

    /*
     * Current internal state of the line reader
     */
    protected State   state = State.NORMAL;
    protected State previousState;

    protected Thread readLineThread;

    protected String originalPrompt;

    protected String oldBuf;
    protected int oldColumns;
    protected String oldPrompt;
    protected String[] oldPost;

    protected String[] post;

    protected int cursorPos;


    protected Map<Operation, Widget> dispatcher;

    protected int count;
    protected int repeatCount;
    protected boolean isArgDigit;

    public ConsoleReaderImpl(Console console) throws IOException {
        this(console, null, null);
    }

    public ConsoleReaderImpl(Console console, String appName, URL inputrc) throws IOException {
        this(console, appName, inputrc, null);
    }

    public ConsoleReaderImpl(Console console, String appName, URL inputrc, Map<String, String> variables) {
        checkNotNull(console);
        this.console = console;
        if (appName == null) {
            appName = "JLine";
        }
        if (inputrc == null) {
            File f = new File(System.getProperty("user.home"), ".inputrc");
            if (!f.exists()) {
                f = new File("/etc/inputrc");
            }
            try {
                inputrc = f.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException();
            }
        }
        this.appName = appName;
        this.inputrc = inputrc;
        if (variables != null) {
            this.variables.putAll(variables);
        }
        this.consoleKeys = new ConsoleKeys(appName, inputrc);

        if (getBoolean(BIND_TTY_SPECIAL_CHARS, true)) {
            Attributes attr = console.getAttributes();
            bindConsoleChars(consoleKeys.getKeyMaps().get(KeyMap.EMACS), attr);
            bindConsoleChars(consoleKeys.getKeyMaps().get(KeyMap.VI_INSERT), attr);
        }
        dispatcher = createDispatcher();
    }

    /**
     * Bind special chars defined by the console instead of
     * the default bindings
     */
    protected static void bindConsoleChars(KeyMap keyMap, Attributes attr) {
        if (attr != null) {
            rebind(keyMap, Operation.BACKWARD_DELETE_CHAR,
                           /* C-? */ (char) 127, (char) attr.getControlChar(ControlChar.VERASE));
            rebind(keyMap, Operation.UNIX_WORD_RUBOUT,
                           /* C-W */ (char) 23,  (char) attr.getControlChar(ControlChar.VWERASE));
            rebind(keyMap, Operation.UNIX_LINE_DISCARD,
                           /* C-U */ (char) 21, (char) attr.getControlChar(ControlChar.VKILL));
            rebind(keyMap, Operation.QUOTED_INSERT,
                           /* C-V */ (char) 22, (char) attr.getControlChar(ControlChar.VLNEXT));
        }
    }

    protected static void rebind(KeyMap keyMap, Operation operation, char prevBinding, char newBinding) {
        if (prevBinding > 0 && prevBinding < 255) {
            if (keyMap.getBound("" + prevBinding) == operation) {
                keyMap.bind("" + prevBinding, Operation.SELF_INSERT);
                if (newBinding > 0 && newBinding < 255) {
                    keyMap.bind("" + newBinding, operation);
                }
            }
        }
    }

    protected void setupSigCont() {
        Signals.register("CONT", () -> {
//                console.init();
            // TODO: enter raw mode
            redrawLine();
            redisplay();
            flush();
        });
    }

    public Console getConsole() {
        return console;
    }

    public String getAppName() {
        return appName;
    }

    public URL getInputrc() {
        return inputrc;
    }

    public KeyMap getKeys() {
        return consoleKeys.getKeys();
    }

    public CursorBuffer getCursorBuffer() {
        return buf;
    }

    /**
     * Add the specified {@link Completer} to the list of handlers for tab-completion.
     *
     * @param completer the {@link Completer} to add
     * @return true if it was successfully added
     */
    public boolean addCompleter(final Completer completer) {
        return completers.add(completer);
    }

    /**
     * Remove the specified {@link Completer} from the list of handlers for tab-completion.
     *
     * @param completer     The {@link Completer} to remove
     * @return              True if it was successfully removed
     */
    public boolean removeCompleter(final Completer completer) {
        return completers.remove(completer);
    }

    public void setCompleters(Collection<Completer> completers) {
        checkNotNull(completers);
        this.completers.clear();
        this.completers.addAll(completers);
    }

    /**
     * Returns an unmodifiable list of all the completers.
     */
    public Collection<Completer> getCompleters() {
        return Collections.unmodifiableList(completers);
    }

    public void setCompletionHandler(final CompletionHandler handler) {
        this.completionHandler = checkNotNull(handler);
    }

    public CompletionHandler getCompletionHandler() {
        return this.completionHandler;
    }

    //
    // History
    //

    public void setHistory(final History history) {
        checkNotNull(history);
        this.history = history;
    }

    public History getHistory() {
        return history;
    }

    //
    // Highlighter
    //

    public void setHighlighter(Highlighter highlighter) {
        this.highlighter = highlighter;
    }

    public Highlighter getHighlighter() {
        return highlighter;
    }


    //
    // Line Reading
    //

    /**
     * Read the next line and return the contents of the buffer.
     */
    public String readLine() throws UserInterruptException, EndOfFileException {
        return readLine(null, null, null);
    }

    /**
     * Read the next line with the specified character mask. If null, then
     * characters will be echoed. If 0, then no characters will be echoed.
     */
    public String readLine(Character mask) throws UserInterruptException, EndOfFileException {
        return readLine(null, mask, null);
    }

    public String readLine(String prompt) throws UserInterruptException, EndOfFileException {
        return readLine(prompt, null, null);
    }

    /**
     * Read a line from the <i>in</i> {@link InputStream}, and return the line
     * (without any trailing newlines).
     *
     * @param prompt    The prompt to issue to the console, may be null.
     * @return          A line that is read from the console, or null if there was null input (e.g., <i>CTRL-D</i>
     *                  was pressed).
     */
    public String readLine(String prompt, Character mask) throws UserInterruptException, EndOfFileException {
        return readLine(prompt, mask, null);
    }

    /**
     * Read a line from the <i>in</i> {@link InputStream}, and return the line
     * (without any trailing newlines).
     *
     * @param prompt    The prompt to issue to the console, may be null.
     * @return          A line that is read from the console, or null if there was null input (e.g., <i>CTRL-D</i>
     *                  was pressed).
     */
    public String readLine(String prompt, Character mask, String buffer) throws UserInterruptException, EndOfFileException {
        // prompt may be null
        // mask may be null
        // buffer may be null

        Thread readLineThread = Thread.currentThread();
        SignalHandler previousIntrHandler = null;
        SignalHandler previousWinchHandler = null;
        Attributes originalAttributes = null;
        try {
            previousIntrHandler = console.handle(Signal.INT, signal -> readLineThread.interrupt());
            previousWinchHandler = console.handle(Signal.WINCH, signal -> {
                // TODO: fix possible threading issue
                size = console.getSize();
                redisplay();
            });
            originalAttributes = console.enterRawMode();

            this.mask = mask;

            /*
             * This is the accumulator for VI-mode repeat count. That is, while in
             * move mode, if you type 30x it will delete 30 characters. This is
             * where the "30" is accumulated until the command is struck.
             */
            repeatCount = 0;

            state = State.NORMAL;

            pushBackChar.clear();

            size = console.getSize();
            cursorPos = 0;

            setPrompt(prompt);
            buf.clear();
            if (buffer != null) {
                buf.write(buffer);
            }
            originalPrompt = this.prompt;

            // Draw initial prompt
            redrawLine();
            redisplay();
            flush();

            while (true) {

                Object o = readBinding(getKeys());
                if (o == null) {
                    return null;
                }
                int c = 0;
                if (opBuffer.length() > 0) {
                    c = opBuffer.codePointBefore(opBuffer.length());
                }
                Log.trace("Binding: ", o);


                // Handle macros
                if (o instanceof String) {
                    String macro = (String) o;
                    for (int i = 0; i < macro.length(); i++) {
                        pushBackChar.push(macro.charAt(macro.length() - 1 - i));
                    }
                    opBuffer.setLength(0);
                    continue;
                }

                // Handle custom callbacks
                // TODO: merge that with the usual dispatch
                if (o instanceof Widget) {
                    ((Widget) o).apply(this);
                    opBuffer.setLength(0);
                    continue;
                }

                // Cache console size for the duration of the binding processing
                this.size = console.getSize();

                // Search mode.
                //
                // Note that we have to do this first, because if there is a command
                // not linked to a search command, we leave the search mode and fall
                // through to the normal state.
                if (state == State.SEARCH || state == State.FORWARD_SEARCH) {
                    // TODO: check the isearch-terminators variable terminating the search
                    switch ( ((Operation) o )) {
                        case ABORT:
                            state = State.NORMAL;
                            buf.clear();
                            buf.write(originalBuffer.buffer);
                            buf.cursor = originalBuffer.cursor;
                            break;

                        case REVERSE_SEARCH_HISTORY:
                            state = State.SEARCH;
                            if (searchTerm.length() == 0) {
                                searchTerm.append(previousSearchTerm);
                            }

                            if (searchIndex > 0) {
                                searchIndex = searchBackwards(searchTerm.toString(), searchIndex);
                            }
                            break;

                        case FORWARD_SEARCH_HISTORY:
                            state = State.FORWARD_SEARCH;
                            if (searchTerm.length() == 0) {
                                searchTerm.append(previousSearchTerm);
                            }

                            if (searchIndex > -1 && searchIndex < history.size() - 1) {
                                searchIndex = searchForwards(searchTerm.toString(), searchIndex);
                            }
                            break;

                        case BACKWARD_DELETE_CHAR:
                            if (searchTerm.length() > 0) {
                                searchTerm.deleteCharAt(searchTerm.length() - 1);
                                if (state == State.SEARCH) {
                                    searchIndex = searchBackwards(searchTerm.toString());
                                } else {
                                    searchIndex = searchForwards(searchTerm.toString());
                                }
                            }
                            break;

                        case SELF_INSERT:
                            searchTerm.appendCodePoint(c);
                            if (state == State.SEARCH) {
                                searchIndex = searchBackwards(searchTerm.toString());
                            } else {
                                searchIndex = searchForwards(searchTerm.toString());
                            }
                            break;

                        default:
                            // Set buffer and cursor position to the found string.
                            if (searchIndex != -1) {
                                history.moveTo(searchIndex);
                            }
                            if (o != Operation.ACCEPT_LINE) {
                                o = null;
                            }
                            state = State.NORMAL;
                            break;
                    }

                    // if we're still in search mode, print the search status
                    if (state == State.SEARCH || state == State.FORWARD_SEARCH) {
                        if (searchTerm.length() == 0) {
                            if (state == State.SEARCH) {
                                printSearchStatus("", "");
                            } else {
                                printForwardSearchStatus("", "");
                            }
                            searchIndex = -1;
                        } else {
                            if (searchIndex == -1) {
                                beep();
                                printSearchStatus(searchTerm.toString(), "");
                            } else if (state == State.SEARCH) {
                                printSearchStatus(searchTerm.toString(), history.get(searchIndex));
                            } else {
                                printForwardSearchStatus(searchTerm.toString(), history.get(searchIndex));
                            }
                        }
                    }
                    // otherwise, restore the line
                    else {
                        restoreLine();
                    }
                }
                if (state != State.SEARCH && state != State.FORWARD_SEARCH) {
                    /*
                     * If this is still false at the end of the switch, then
                     * we reset our repeatCount to 0.
                     */
                    isArgDigit = false;

                    /*
                     * Every command that can be repeated a specified number
                     * of times, needs to know how many times to repeat, so
                     * we figure that out here.
                     */
                    count = (repeatCount == 0) ? 1 : repeatCount;

                    if (o instanceof Operation) {
                        Operation op = (Operation) o;
                        /*
                         * Current location of the cursor (prior to the operation).
                         * These are used by vi *-to operation (e.g. delete-to)
                         * so we know where we came from.
                         */
                        int     cursorStart = buf.cursor;
                        previousState   = state;

                        /*
                         * If we are on a "vi" movement based operation, then we
                         * need to restrict the sets of inputs pretty heavily.
                         */
                        if (state == State.VI_CHANGE_TO
                                || state == State.VI_YANK_TO
                                || state == State.VI_DELETE_TO) {

                            op = viDeleteChangeYankToRemap(op);
                        }

                        Widget widget = dispatcher.get(op);
                        if (widget != null) {
                            widget.apply(this);
                        } else {
                            // TODO: what should we do there ?
                            beep();
                        }

                        switch (state) {
                            case DONE:
                                return finishBuffer();
                            case EOF:
                                throw new EndOfFileException();
                            case INTERRUPT:
                                throw new UserInterruptException(buf.buffer.toString());
                        }

                        /*
                         * If we were in a yank-to, delete-to, move-to
                         * when this operation started, then fall back to
                         */
                        if (previousState != State.NORMAL) {
                            if (previousState == State.VI_DELETE_TO) {
                                viDeleteTo(cursorStart, buf.cursor, false);
                            }
                            else if (previousState == State.VI_CHANGE_TO) {
                                viDeleteTo(cursorStart, buf.cursor, true);
                                consoleKeys.setKeyMap(KeyMap.VI_INSERT);
                            }
                            else if (previousState == State.VI_YANK_TO) {
                                viYankTo(cursorStart, buf.cursor);
                            }
                            state = State.NORMAL;
                        }

                        /*
                         * Another subtly. The check for the NORMAL state is
                         * to ensure that we do not clear out the repeat
                         * count when in delete-to, yank-to, or move-to modes.
                         */
                        if (state == State.NORMAL && !isArgDigit) {
                            /*
                             * If the operation performed wasn't a vi argument
                             * digit, then clear out the current repeatCount;
                             */
                            repeatCount = 0;
                        }

                        if (state != State.SEARCH && state != State.FORWARD_SEARCH) {
                            originalBuffer = null;
                            previousSearchTerm = "";
                            searchTerm = null;
                            searchIndex = -1;
                        }
                    }
                }
                redisplay();
                flush();
                opBuffer.setLength(0);
            }
        } catch (IOError e) {
            if (e.getCause() instanceof InterruptedIOException) {
                throw new UserInterruptException(buf.buffer.toString());
            } else {
                throw e;
            }
        }
        finally {
            cleanup();
            if (originalAttributes != null) {
                console.setAttributes(originalAttributes);
            }
            if (previousIntrHandler != null) {
                console.handle(Signal.INT, previousIntrHandler);
            }
            if (previousWinchHandler != null) {
                console.handle(Signal.WINCH, previousWinchHandler);
            }
        }
    }

    //
    // Helper methods
    //

    protected void setPrompt(final String prompt) {
        this.prompt = prompt != null ? prompt : "";
        this.promptLen = wcwidth(lastLine(AnsiHelper.strip(this.prompt)), 0);
    }

    /**
     * Erase the current line.
     *
     * @return false if we failed (e.g., the buffer was empty)
     */
    protected void resetLine() {
        if (buf.cursor == 0) {
            beep();
        } else {
            StringBuilder killed = new StringBuilder();
            while (buf.cursor > 0) {
                char c = buf.current();
                if (c == 0) {
                    break;
                }
                killed.append(c);
                backspace();
            }
            String copy = killed.reverse().toString();
            killRing.addBackwards(copy);
        }
    }

    int wcwidth(CharSequence str, int pos) {
        return wcwidth(str, 0, str.length(), pos);
    }

    int wcwidth(CharSequence str, int start, int end, int pos) {
        int cur = pos;
        for (int i = start; i < end;) {
            int ucs;
            char c1 = str.charAt(i++);
            if (!Character.isHighSurrogate(c1) || i >= end) {
                ucs = c1;
            } else {
                char c2 = str.charAt(i);
                if (Character.isLowSurrogate(c2)) {
                    i++;
                    ucs = Character.toCodePoint(c1, c2);
                } else {
                    ucs = c1;
                }
            }
            cur += wcwidth(ucs, cur);
        }
        return cur - pos;
    }

    int wcwidth(int ucs, int pos) {
        if (ucs == '\t') {
            return nextTabStop(pos);
        } else if (ucs < 32) {
            return 2;
        } else  {
            int w = WCWidth.wcwidth(ucs);
            return w > 0 ? w : 0;
        }
    }

    int nextTabStop(int pos) {
        int tabWidth = TAB_WIDTH;
        int width = size.getColumns();
        int mod = (pos + tabWidth - 1) % tabWidth;
        int npos = pos + tabWidth - mod;
        return npos < width ? npos - pos : width - pos;
    }

    int getCursorPosition() {
        return promptLen + wcwidth(buf.buffer, 0, buf.cursor, promptLen);
    }

    /**
     * Returns the text after the last '\n'.
     * prompt is returned if no '\n' characters are present.
     * null is returned if prompt is null.
     */
    protected String lastLine(String str) {
        // TODO: use ansi splitter to support ansi sequences propagation across lines
        if (str == null) return "";
        int last = str.lastIndexOf("\n");

        if (last >= 0) {
            return str.substring(last + 1, str.length());
        }

        return str;
    }

    /**
     * Move the cursor position to the specified absolute index.
     */
    public boolean setCursorPosition(final int position) {
        if (position == buf.cursor) {
            return true;
        }
        return moveBufferCursor(position - buf.cursor) != 0;
    }

    protected void setBuffer(CursorBuffer buffer) {
        setBuffer(buffer.buffer.toString());
        setCursorPosition(buffer.cursor);
    }

    /**
     * Set the current buffer's content to the specified {@link String}. The
     * visual console will be modified to show the current buffer.
     *
     * @param buffer the new contents of the buffer.
     */
    protected void setBuffer(final String buffer) {
        // don't bother modifying it if it is unchanged
        if (buffer.equals(buf.buffer.toString())) {
            return;
        }

        // obtain the difference between the current buffer and the new one
        int sameIndex = 0;

        for (int i = 0, l1 = buffer.length(), l2 = buf.buffer.length(); (i < l1)
            && (i < l2); i++) {
            if (buffer.charAt(i) == buf.buffer.charAt(i)) {
                sameIndex++;
            }
            else {
                break;
            }
        }

        int diff = buf.cursor - sameIndex;
        if (diff < 0) { // we can't backspace here so try from the end of the buffer
            endOfLine();
            diff = buf.buffer.length() - sameIndex;
        }

        backspace(diff); // go back for the differences
        killLine(); // clear to the end of the line
        buf.buffer.setLength(sameIndex); // the new length
        putString(buffer.substring(sameIndex)); // append the differences
    }

    protected void setBufferKeepPos(final String buffer) {
        int pos = buf.cursor;
        setBuffer(buffer);
        setCursorPosition(pos);
    }

    /**
     * Clear the line and redraw it.
     */
    public void redrawLine() {
        oldBuf = "";
        oldPrompt = "";
        oldPost = null;
    }

    /**
     * Clear the buffer and add its contents to the history.
     *
     * @return the former contents of the buffer.
     */
    final String finishBuffer() { // FIXME: Package protected because used by tests
        String str = buf.buffer.toString();
        String historyLine = str;

        if (!getBoolean(DISABLE_EVENT_EXPANSION, false)) {
            try {
                str = expandEvents(str);
                // all post-expansion occurrences of '!' must have been escaped, so re-add escape to each
                historyLine = str.replace("!", "\\!");
                // only leading '^' results in expansion, so only re-add escape for that case
                historyLine = historyLine.replaceAll("^\\^", "\\\\^");
            } catch(IllegalArgumentException e) {
                Log.error("Could not expand event", e);
                beep();
                buf.clear();
                str = "";
            }
        }

        // we only add it to the history if the buffer is not empty
        // and if mask is null, since having a mask typically means
        // the string was a password. We clear the mask after this call
        if (str.length() > 0) {
            if (mask == null && !getBoolean(DISABLE_HISTORY, false)) {
                history.add(historyLine);
            }
            else {
                mask = null;
            }
        }
        return str;
    }

    /**
     * Expand event designator such as !!, !#, !3, etc...
     * See http://www.gnu.org/software/bash/manual/html_node/Event-Designators.html
     */
    @SuppressWarnings("fallthrough")
    protected String expandEvents(String str) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\\':
                    // any '\!' should be considered an expansion escape, so skip expansion and strip the escape character
                    // a leading '\^' should be considered an expansion escape, so skip expansion and strip the escape character
                    // otherwise, add the escape
                    if (i + 1 < str.length()) {
                        char nextChar = str.charAt(i+1);
                        if (nextChar == '!' || (nextChar == '^' && i == 0)) {
                            c = nextChar;
                            i++;
                        }
                    }
                    sb.append(c);
                    break;
                case '!':
                    if (i + 1 < str.length()) {
                        c = str.charAt(++i);
                        boolean neg = false;
                        String rep = null;
                        int i1, idx;
                        switch (c) {
                            case '!':
                                if (history.size() == 0) {
                                    throw new IllegalArgumentException("!!: event not found");
                                }
                                rep = history.get(history.index() - 1).toString();
                                break;
                            case '#':
                                sb.append(sb.toString());
                                break;
                            case '?':
                                i1 = str.indexOf('?', i + 1);
                                if (i1 < 0) {
                                    i1 = str.length();
                                }
                                String sc = str.substring(i + 1, i1);
                                i = i1;
                                idx = searchBackwards(sc);
                                if (idx < 0) {
                                    throw new IllegalArgumentException("!?" + sc + ": event not found");
                                } else {
                                    rep = history.get(idx).toString();
                                }
                                break;
                            case '$':
                                if (history.size() == 0) {
                                    throw new IllegalArgumentException("!$: event not found");
                                }
                                String previous = history.get(history.index() - 1).toString().trim();
                                int lastSpace = previous.lastIndexOf(' ');
                                if(lastSpace != -1) {
                                    rep = previous.substring(lastSpace+1);
                                } else {
                                    rep = previous;
                                }
                                break;
                            case ' ':
                            case '\t':
                                sb.append('!');
                                sb.append(c);
                                break;
                            case '-':
                                neg = true;
                                i++;
                                // fall through
                            case '0':
                            case '1':
                            case '2':
                            case '3':
                            case '4':
                            case '5':
                            case '6':
                            case '7':
                            case '8':
                            case '9':
                                i1 = i;
                                for (; i < str.length(); i++) {
                                    c = str.charAt(i);
                                    if (c < '0' || c > '9') {
                                        break;
                                    }
                                }
                                try {
                                    idx = Integer.parseInt(str.substring(i1, i));
                                } catch (NumberFormatException e) {
                                    throw new IllegalArgumentException((neg ? "!-" : "!") + str.substring(i1, i) + ": event not found");
                                }
                                if (neg && idx > 0 && idx <= history.size()) {
                                    rep = (history.get(history.index() - idx)).toString();
                                } else if (!neg && idx > history.index() - history.size() && idx <= history.index()) {
                                    rep = (history.get(idx - 1)).toString();
                                } else {
                                    throw new IllegalArgumentException((neg ? "!-" : "!") + str.substring(i1, i) + ": event not found");
                                }
                                break;
                            default:
                                String ss = str.substring(i);
                                i = str.length();
                                idx = searchBackwards(ss, history.index(), true);
                                if (idx < 0) {
                                    throw new IllegalArgumentException("!" + ss + ": event not found");
                                } else {
                                    rep = history.get(idx).toString();
                                }
                                break;
                        }
                        if (rep != null) {
                            sb.append(rep);
                        }
                    } else {
                        sb.append(c);
                    }
                    break;
                case '^':
                    if (i == 0) {
                        int i1 = str.indexOf('^', i + 1);
                        int i2 = str.indexOf('^', i1 + 1);
                        if (i2 < 0) {
                            i2 = str.length();
                        }
                        if (i1 > 0 && i2 > 0) {
                            String s1 = str.substring(i + 1, i1);
                            String s2 = str.substring(i1 + 1, i2);
                            String s = history.get(history.index() - 1).toString().replace(s1, s2);
                            sb.append(s);
                            i = i2 + 1;
                            break;
                        }
                    }
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        String result = sb.toString();
        if (!str.equals(result)) {
            println(result);
            flush();
        }
        return result;

    }

    /**
     * Write out the specified string to the buffer and the output stream.
     */
    public void putString(final CharSequence str) {
        buf.write(str);
    }

    /**
     * Flush the console output stream. This is important for printout out single characters (like a backspace or
     * keyboard) that we want the console to handle immediately.
     */
    public void flush() {
        console.writer().flush();
    }

    /**
     * Issue <em>num</em> backspaces.
     *
     * @return the number of characters backed up
     */
    protected int backspace(final int num) {
        if (buf.cursor == 0) {
            return 0;
        }

        int count = - moveBufferCursor(-num);
        buf.buffer.delete(buf.cursor, buf.cursor + count);

        return count;
    }

    /**
     * Issue a backspace.
     *
     * @return true if successful
     */
    public boolean backspace() {
        return backspace(1) == 1;
    }

    /**
     * Delete the character at the current position and redraw the remainder of the buffer.
     */
    protected boolean deleteCurrentCharacter() {
        if (buf.length() == 0 || buf.cursor == buf.length()) {
            return false;
        }

        buf.buffer.deleteCharAt(buf.cursor);
        return true;
    }
    
    /**
     * This method is calling while doing a delete-to ("d"), change-to ("c"),
     * or yank-to ("y") and it filters out only those movement operations
     * that are allowable during those operations. Any operation that isn't
     * allow drops you back into movement mode.
     *
     * @param op The incoming operation to remap
     * @return The remaped operation
     */
    protected Operation viDeleteChangeYankToRemap (Operation op) {
        switch (op) {
            case VI_EOF_MAYBE:
            case ABORT:
            case BACKWARD_CHAR:
            case FORWARD_CHAR:
            case END_OF_LINE:
            case VI_MATCH:
            case VI_BEGINNING_OF_LINE_OR_ARG_DIGIT:
            case VI_ARG_DIGIT:
            case VI_PREV_WORD:
            case VI_END_WORD:
            case VI_CHAR_SEARCH:
            case VI_NEXT_WORD:
            case VI_FIRST_PRINT:
            case VI_GOTO_MARK:
            case VI_COLUMN:
            case VI_DELETE_TO:
            case VI_YANK_TO:
            case VI_CHANGE_TO:
                return op;

            default:
                return Operation.VI_MOVEMENT_MODE;
        }
    }

    /**
     * Searches forward of the current position for a character and moves
     * the cursor onto it.
     * @param count Number of times to repeat the process.
     * @param ch The character to search for
     * @return true if the char was found, false otherwise
     */
    protected boolean doViCharSearch(int count, int invokeChar, int ch) {
        if (ch < 0 || invokeChar < 0) {
            return false;
        }

        char    searchChar = (char)ch;
        boolean isForward;
        boolean stopBefore;

        /*
         * The character stuff turns out to be hairy. Here is how it works:
         *   f - search forward for ch
         *   F - search backward for ch
         *   t - search forward for ch, but stop just before the match
         *   T - search backward for ch, but stop just after the match
         *   ; - After [fFtT;], repeat the last search, after ',' reverse it
         *   , - After [fFtT;], reverse the last search, after ',' repeat it
         */
        if (invokeChar == ';' || invokeChar == ',') {
            // No recent search done? Then bail
            if (charSearchChar == 0) {
                return false;
            }

            // Reverse direction if switching between ',' and ';'
            if (charSearchLastInvokeChar == ';' || charSearchLastInvokeChar == ',') {
                if (charSearchLastInvokeChar != invokeChar) {
                    charSearchFirstInvokeChar = switchCase(charSearchFirstInvokeChar);
                }
            }
            else {
                if (invokeChar == ',') {
                    charSearchFirstInvokeChar = switchCase(charSearchFirstInvokeChar);
                }
            }

            searchChar = charSearchChar;
        }
        else {
            charSearchChar            = searchChar;
            charSearchFirstInvokeChar = (char) invokeChar;
        }

        charSearchLastInvokeChar = (char)invokeChar;

        isForward = Character.isLowerCase(charSearchFirstInvokeChar);
        stopBefore = (Character.toLowerCase(charSearchFirstInvokeChar) == 't');

        boolean ok = false;

        if (isForward) {
            while (count-- > 0) {
                int pos = buf.cursor + 1;
                while (pos < buf.buffer.length()) {
                    if (buf.buffer.charAt(pos) == searchChar) {
                        setCursorPosition(pos);
                        ok = true;
                        break;
                    }
                    ++pos;
                }
            }

            if (ok) {
                if (stopBefore)
                    moveBufferCursor(-1);

                /*
                 * When in yank-to, move-to, del-to state we actually want to
                 * go to the character after the one we landed on to make sure
                 * that the character we ended up on is included in the
                 * operation
                 */
                if (isInViMoveOperationState()) {
                    moveBufferCursor(1);
                }
            }
        }
        else {
            while (count-- > 0) {
                int pos = buf.cursor - 1;
                while (pos >= 0) {
                    if (buf.buffer.charAt(pos) == searchChar) {
                        setCursorPosition(pos);
                        ok = true;
                        break;
                    }
                    --pos;
                }
            }

            if (ok && stopBefore)
                moveBufferCursor(1);
        }

        return ok;
    }

    protected char switchCase(char ch) {
        if (Character.isUpperCase(ch)) {
            return Character.toLowerCase(ch);
        }
        return Character.toUpperCase(ch);
    }

    /**
     * @return true if line reader is in the middle of doing a change-to
     *   delete-to or yank-to.
     */
    protected boolean isInViMoveOperationState() {
        return state == State.VI_CHANGE_TO
            || state == State.VI_DELETE_TO
            || state == State.VI_YANK_TO;
    }

    protected void viNextWord() {
        if (!doViNextWord(count)) {
            beep();
        }
    }

    /**
     * This is a close facsimile of the actual vi next word logic.
     * As with viPreviousWord() this probably needs to be improved
     * at some point.
     *
     * @param count number of iterations
     * @return true if the move was successful, false otherwise
     */
    protected boolean doViNextWord(int count) {
        int pos = buf.cursor;
        int end = buf.buffer.length();

        if (pos == end) {
            return false;
        }

        for (int i = 0; pos < end && i < count; i++) {
            // Skip over letter/digits
            while (pos < end && !isDelimiter(buf.buffer.charAt(pos))) {
                ++pos;
            }

            /*
             * Don't you love special cases? During delete-to and yank-to
             * operations the word movement is normal. However, during a
             * change-to, the trailing spaces behind the last word are
             * left in tact.
             */
            if (i < (count-1) || !(state == State.VI_CHANGE_TO)) {
                while (pos < end && isDelimiter(buf.buffer.charAt(pos))) {
                    ++pos;
                }
            }
        }

        setCursorPosition(pos);
        return true;
    }

    /**
     * Implements a close facsimile of the vi end-of-word movement.
     * If the character is on white space, it takes you to the end
     * of the next word.  If it is on the last character of a word
     * it takes you to the next of the next word.  Any other character
     * of a word, takes you to the end of the current word.
     */
    protected void viEndWord() {
        int pos = buf.cursor;
        int end = buf.buffer.length();

        // TODO: refactor to use buf.current() / moveBufferCursor
        for (int i = 0; pos < end && i < count; i++) {
            if (pos < (end-1)
                    && !isDelimiter(buf.buffer.charAt(pos))
                    && isDelimiter(buf.buffer.charAt (pos+1))) {
                ++pos;
            }

            // If we are on white space, then move back.
            while (pos < end && isDelimiter(buf.buffer.charAt(pos))) {
                ++pos;
            }

            while (pos < (end-1) && !isDelimiter(buf.buffer.charAt(pos+1))) {
                ++pos;
            }
        }
        setCursorPosition(pos);
    }

    @SuppressWarnings("StatementWithEmptyBody")
    protected void backwardWord() {
        while (isDelimiter(buf.current()) && (moveBufferCursor(-1) != 0));
        while (!isDelimiter(buf.current()) && (moveBufferCursor(-1) != 0));
    }

    @SuppressWarnings("StatementWithEmptyBody")
    protected void forwardWord() {
        while (isDelimiter(buf.nextChar()) && (moveBufferCursor(1) != 0));
        while (!isDelimiter(buf.nextChar()) && (moveBufferCursor(1) != 0));
    }

    /**
     * Deletes to the beginning of the word that the cursor is sitting on.
     * If the cursor is on white-space, it deletes that and to the beginning
     * of the word before it.  If the user is not on a word or whitespace
     * it deletes up to the end of the previous word.
     */
    protected void unixWordRubout() {
        StringBuilder killed = new StringBuilder();

        for (int count = this.count; count > 0; --count) {
            if (buf.cursor == 0) {
                beep();
                return;
            }

            while (isWhitespace(buf.current())) {
                char c = buf.current();
                if (c == 0) {
                    break;
                }

                killed.append(c);
                backspace();
            }

            while (!isWhitespace(buf.current())) {
                char c = buf.current();
                if (c == 0) {
                    break;
                }

                killed.append(c);
                backspace();
            }
        }

        String copy = killed.reverse().toString();
        killRing.addBackwards(copy);
    }

    protected void insertComment() {
        doInsertComment(false);
    }

    protected void viInsertComment() {
        doInsertComment(true);
    }

    protected void doInsertComment(boolean isViMode) {
        String comment = getVariable(COMMENT_BEGIN);
        if (comment == null) {
            comment = "#";
        }
        beginningOfLine();
        putString(comment);
        if (isViMode) {
            setKeyMap(KeyMap.VI_INSERT);
        }
        acceptLine();
    }

    /**
     * Implements vi search ("/" or "?").
     */
    @SuppressWarnings("fallthrough")
    protected void viSearch() {
        char searchChar = opBuffer.charAt(0);
        boolean isForward = (searchChar == '/');

        /*
         * This is a little gross, I'm sure there is a more appropriate way
         * of saving and restoring state.
         */
        CursorBuffer origBuffer = buf.copy();

        // Clear the contents of the current line and
        setCursorPosition (0);
        killLine();

        // Our new "prompt" is the character that got us into search mode.
        putString(Character.toString(searchChar));
        flush();

        boolean isAborted = false;
        boolean isComplete = false;

        /*
         * Readline doesn't seem to do any special character map handling
         * here, so I think we are safe.
         */
        int ch = -1;
        while (!isAborted && !isComplete && (ch = readCharacter()) != -1) {
            switch (ch) {
                case '\033':  // ESC
                    /*
                     * The ESC behavior doesn't appear to be readline behavior,
                     * but it is a little tweak of my own. I like it.
                     */
                    isAborted = true;
                    break;
                case '\010':  // Backspace
                case '\177':  // Delete
                    backspace();
                    /*
                     * Backspacing through the "prompt" aborts the search.
                     */
                    if (buf.cursor == 0) {
                        isAborted = true;
                    }
                    break;
                case '\012': // NL
                case '\015': // CR
                    isComplete = true;
                    break;
                default:
                    putString(Character.toString((char) ch));
            }

            flush();
        }

        // If we aborted, then put ourself at the end of the original buffer.
        if (ch == -1 || isAborted) {
            setCursorPosition(0);
            killLine();
            putString(origBuffer.buffer);
            setCursorPosition(origBuffer.cursor);
            return;
        }

        /*
         * The first character of the buffer was the search character itself
         * so we discard it.
         */
        String searchTerm = buf.buffer.substring(1);
        int idx = -1;

        /*
         * The semantics of the history thing is gross when you want to
         * explicitly iterate over entries (without an iterator) as size()
         * returns the actual number of entries in the list but get()
         * doesn't work the way you think.
         */
        int end   = history.index();
        int start = (end <= history.size()) ? 0 : end - history.size();

        if (isForward) {
            for (int i = start; i < end; i++) {
                if (history.get(i).toString().contains(searchTerm)) {
                    idx = i;
                    break;
                }
            }
        }
        else {
            for (int i = end-1; i >= start; i--) {
                if (history.get(i).toString().contains(searchTerm)) {
                    idx = i;
                    break;
                }
            }
        }

        /*
         * No match? Then restore what we were working on, but make sure
         * the cursor is at the beginning of the line.
         */
        if (idx == -1) {
            setCursorPosition(0);
            killLine();
            putString(origBuffer.buffer);
            setCursorPosition(0);
            return;
        }

        /*
         * Show the match.
         */
        setCursorPosition(0);
        killLine();
        putString(history.get(idx));
        setCursorPosition(0);
        flush();

        /*
         * While searching really only the "n" and "N" keys are interpreted
         * as movement, any other key is treated as if you are editing the
         * line with it, so we return it back up to the caller for interpretation.
         */
        isComplete = false;
        while (!isComplete && (ch = readCharacter()) != -1) {
            boolean forward = isForward;
            switch (ch) {
                case 'p': case 'P':
                    forward = !isForward;
                    // Fallthru
                case 'n': case 'N':
                    boolean isMatch = false;
                    if (forward) {
                        for (int i = idx+1; !isMatch && i < end; i++) {
                            if (history.get(i).toString().contains(searchTerm)) {
                                idx = i;
                                isMatch = true;
                            }
                        }
                    }
                    else {
                        for (int i = idx - 1; !isMatch && i >= start; i--) {
                            if (history.get(i).toString().contains(searchTerm)) {
                                idx = i;
                                isMatch = true;
                            }
                        }
                    }
                    if (isMatch) {
                        setCursorPosition(0);
                        killLine();
                        putString(history.get(idx));
                        setCursorPosition(0);
                    }
                    break;
                default:
                    isComplete = true;
            }
            flush();
        }

        /*
         * Complete?
         */
        pushBackChar.push((char) ch);
    }

    protected void insertCloseCurly() {
        insertClose("}");
    }

    protected void insertCloseParen() {
        insertClose(")");
    }

    protected void insertCloseSquare() {
        insertClose("]");
    }

    protected void insertClose(String s) {
        putString(s);

        int closePosition = buf.cursor;

        moveBufferCursor(-1);
        doViMatch();
        flush();

        peekCharacter(BLINK_MATCHING_PAREN_TIMEOUT);

        setCursorPosition(closePosition);
    }

    protected void viMatch() {
        if (!doViMatch()) {
            beep();
        }
    }
    
    /**
     * Implements vi style bracket matching ("%" command). The matching
     * bracket for the current bracket type that you are sitting on is matched.
     * The logic works like so:
     * @return true if it worked, false if the cursor was not on a bracket
     *   character or if there was no matching bracket.
     */
    protected boolean doViMatch() {
        int pos        = buf.cursor;

        if (pos == buf.length()) {
            return false;
        }

        int type       = getBracketType(buf.buffer.charAt (pos));
        int move       = (type < 0) ? -1 : 1;
        int count      = 1;

        if (type == 0)
            return false;

        while (count > 0) {
            pos += move;

            // Fell off the start or end.
            if (pos < 0 || pos >= buf.buffer.length ()) {
                return false;
            }

            int curType = getBracketType(buf.buffer.charAt (pos));
            if (curType == type) {
                ++count;
            }
            else if (curType == -type) {
                --count;
            }
        }

        /*
         * Slight adjustment for delete-to, yank-to, change-to to ensure
         * that the matching paren is consumed
         */
        if (move > 0 && isInViMoveOperationState())
            ++pos;

        setCursorPosition(pos);
        return true;
    }

    /**
     * Given a character determines what type of bracket it is (paren,
     * square, curly, or none).
     * @param ch The character to check
     * @return 1 is square, 2 curly, 3 parent, or zero for none.  The value
     *   will be negated if it is the closing form of the bracket.
     */
    protected int getBracketType (char ch) {
        switch (ch) {
            case '[': return  1;
            case ']': return -1;
            case '{': return  2;
            case '}': return -2;
            case '(': return  3;
            case ')': return -3;
            default:
                return 0;
        }
    }

    protected void deletePreviousWord() {
        StringBuilder killed = new StringBuilder();
        char c;

        while (isDelimiter((c = buf.current()))) {
            if (c == 0) {
                break;
            }

            killed.append(c);
            backspace();
        }

        while (!isDelimiter((c = buf.current()))) {
            if (c == 0) {
                break;
            }

            killed.append(c);
            backspace();
        }

        String copy = killed.reverse().toString();
        killRing.addBackwards(copy);
    }

    protected void deleteNextWord() {
        StringBuilder killed = new StringBuilder();
        char c;

        while (isDelimiter((c = buf.nextChar()))) {
            if (c == 0) {
                break;
            }
            killed.append(c);
            delete();
        }

        while (!isDelimiter((c = buf.nextChar()))) {
            if (c == 0) {
                break;
            }
            killed.append(c);
            delete();
        }

        String copy = killed.toString();
        killRing.add(copy);
    }

    protected void capitalizeWord() {
        boolean first = true;
        int i = 1;
        char c;
        while (buf.cursor + i  - 1< buf.length() && !isDelimiter((c = buf.buffer.charAt(buf.cursor + i - 1)))) {
            buf.buffer.setCharAt(buf.cursor + i - 1, first ? Character.toUpperCase(c) : Character.toLowerCase(c));
            first = false;
            i++;
        }
        moveBufferCursor(i - 1);
    }

    protected void upCaseWord() {
        int i = 1;
        char c;
        while (buf.cursor + i - 1 < buf.length() && !isDelimiter((c = buf.buffer.charAt(buf.cursor + i - 1)))) {
            buf.buffer.setCharAt(buf.cursor + i - 1, Character.toUpperCase(c));
            i++;
        }
        moveBufferCursor(i - 1);
    }

    protected void downCaseWord() {
        int i = 1;
        char c;
        while (buf.cursor + i - 1 < buf.length() && !isDelimiter((c = buf.buffer.charAt(buf.cursor + i - 1)))) {
            buf.buffer.setCharAt(buf.cursor + i - 1, Character.toLowerCase(c));
            i++;
        }
        moveBufferCursor(i - 1);
    }

    /**
     * Performs character transpose. The character prior to the cursor and the
     * character under the cursor are swapped and the cursor is advanced one
     * character unless you are already at the end of the line.
     */
    protected void transposeChars() {
        for (int count = this.count; count > 0; --count) {
            if (buf.cursor == 0 || buf.cursor == buf.buffer.length()) {
                beep();
                break;
            }

            int first  = buf.cursor-1;
            int second = buf.cursor;

            char tmp = buf.buffer.charAt (first);
            buf.buffer.setCharAt(first, buf.buffer.charAt(second));
            buf.buffer.setCharAt(second, tmp);

            buf.cursor++;
        }
    }

    public boolean isKeyMap(String name) {
        // Current keymap.
        KeyMap map = consoleKeys.getKeys();
        KeyMap mapByName = consoleKeys.getKeyMaps().get(name);

        if (mapByName == null)
            return false;

        /*
         * This may not be safe to do, but there doesn't appear to be a
         * clean way to find this information out.
         */
        return map == mapByName;
    }


    protected void abort() {
        if (searchTerm == null) {
            beep();
            buf.clear();
            println();
            redrawLine();
        }
    }

    protected void backwardChar() {
        if (moveBufferCursor(-count) == 0) {
            beep();
        }
    }

    protected void forwardChar() {
        if (moveBufferCursor(count) == 0) {
            beep();
        }
    }

    /**
     * Move the cursor <i>where</i> characters.
     *
     * @param num   If less than 0, move abs(<i>where</i>) to the left, otherwise move <i>where</i> to the right.
     * @return      The number of spaces we moved
     */
    public int moveBufferCursor(final int num) {
        int where = num;

        if ((buf.cursor == 0) && (where <= 0)) {
            return 0;
        }

        if ((buf.cursor == buf.buffer.length()) && (where >= 0)) {
            return 0;
        }

        if ((buf.cursor + where) < 0) {
            where = -buf.cursor;
        }
        else if ((buf.cursor + where) > buf.buffer.length()) {
            where = buf.buffer.length() - buf.cursor;
        }

        buf.cursor += where;

        return where;
    }

    protected int moveVisualCursorTo(int i1) {
        int i0 = cursorPos;
        if (i0 == i1) return i1;
        int width = size.getColumns();
        int l0 = i0 / width;
        int c0 = i0 % width;
        int l1 = i1 / width;
        int c1 = i1 % width;
        if (l0 == l1 + 1) {
            if (!console.puts(Capability.cursor_up)) {
                console.puts(Capability.parm_up_cursor, 1);
            }
        } else if (l0 > l1) {
            if (!console.puts(Capability.parm_up_cursor, l0 - l1)) {
                for (int i = l1; i < l0; i++) {
                    console.puts(Capability.cursor_up);
                }
            }
        } else if (l0 < l1) {
            console.puts(Capability.carriage_return);
            rawPrint('\n', l1 - l0);
            c0 = 0;
        }
        if (c0 == c1 - 1) {
            console.puts(Capability.cursor_right);
        } else if (c0 == c1 + 1) {
            console.puts(Capability.cursor_left);
        } else if (c0 < c1) {
            if (!console.puts(Capability.parm_right_cursor, c1 - c0)) {
                for (int i = c0; i < c1; i++) {
                    console.puts(Capability.cursor_right);
                }
            }
        } else if (c0 > c1) {
            if (!console.puts(Capability.parm_left_cursor, c0 - c1)) {
                for (int i = c1; i < c0; i++) {
                    console.puts(Capability.cursor_left);
                }
            }
        }
        cursorPos = i1;
        return i1;
    }

    /**
     * Read a character from the console.
     *
     * @return the character, or -1 if an EOF is received.
     */
    public int readCharacter() {
        try {
            int c = NonBlockingReader.READ_EXPIRED;
            while (c == NonBlockingReader.READ_EXPIRED) {
                c = console.reader().read(100l);
            }
            return c;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }
    
    public int peekCharacter(long timeout) {
        try {
            return console.reader().peek(timeout);
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    public int readCharacter(final char... allowed) {
        // if we restrict to a limited set and the current character is not in the set, then try again.
        char c;

        Arrays.sort(allowed); // always need to sort before binarySearch

        //noinspection StatementWithEmptyBody
        while (Arrays.binarySearch(allowed, c = (char) readCharacter()) < 0) {
            // nothing
        }

        return c;
    }

    /**
     * Read from the input stream and decode an operation from the key map.
     *
     * The input stream will be read character by character until a matching
     * binding can be found.  Characters that can't possibly be matched to
     * any binding will be discarded.
     *
     * @param keys the KeyMap to use for decoding the input stream
     * @return the decoded binding or <code>null</code> if the end of
     *         stream has been reached
     */
    public Object readBinding(KeyMap keys) {
        Object o;
        opBuffer.setLength(0);
        do {
            int c = pushBackChar.isEmpty() ? readCharacter() : pushBackChar.pop();
            if (c == -1) {
                return null;
            }
            opBuffer.appendCodePoint(c);

            if (recording) {
                macro += new String(Character.toChars(c));
            }

            if (quotedInsert) {
                o = Operation.SELF_INSERT;
                quotedInsert = false;
            } else {
                o = keys.getBound(opBuffer);
            }

            /*
             * The kill ring keeps record of whether or not the
             * previous command was a yank or a kill. We reset
             * that state here if needed.
             */
            if (!recording && !(o instanceof KeyMap)) {
                if (o != Operation.YANK_POP && o != Operation.YANK) {
                    killRing.resetLastYank();
                }
                if (o != Operation.KILL_LINE && o != Operation.KILL_WHOLE_LINE
                        && o != Operation.BACKWARD_KILL_WORD && o != Operation.KILL_WORD
                        && o != Operation.UNIX_LINE_DISCARD && o != Operation.UNIX_WORD_RUBOUT) {
                    killRing.resetLastKill();
                }
            }

            if (o == Operation.DO_LOWERCASE_VERSION) {
                opBuffer.setLength(opBuffer.length() - 1);
                opBuffer.append(Character.toLowerCase((char) c));
                o = keys.getBound(opBuffer);
            }

            /*
             * The ESC key (#27) is special in that it is ambiguous until
             * you know what is coming next.  The ESC could be a literal
             * escape, like the user entering vi-move mode, or it could
             * be part of a console control sequence.  The following
             * logic attempts to disambiguate things in the same
             * fashion as regular vi or readline.
             *
             * When ESC is encountered and there is no other pending
             * character in the pushback queue, then attempt to peek
             * into the input stream (if the feature is enabled) for
             * 150ms. If nothing else is coming, then assume it is
             * not a console control sequence, but a raw escape.
             */
            if ((keys.getName().equals(KeyMap.VI_INSERT)
                        && opBuffer.length() == 1
                        && c == ESCAPE)
                    || ((state == State.SEARCH || state == State.FORWARD_SEARCH)
                        && opBuffer.length() == 1
                        && getString("search-terminators", "\033\012").indexOf(c) >= 0)
                    && o instanceof KeyMap
                    && pushBackChar.isEmpty()) {
                long t = getLong(ESCAPE_TIMEOUT, 500l);
                if (t > 0 && peekCharacter(t) == READ_EXPIRED) {
                    Object otherKey = ((KeyMap) o).getAnotherKey();
                    if (otherKey == null) {
                        // The next line is in case a binding was set up inside this secondary
                        // KeyMap (like EMACS_META).  For example, a binding could be put
                        // there for an ActionListener for the ESC key.  This way, the var 'o' won't
                        // be null and the code can proceed to let the ActionListener be
                        // handled, below.
                        otherKey = ((KeyMap) o).getBound(Character.toString((char) c));
                    }
                    if (otherKey != null && !(otherKey instanceof KeyMap)) {
                        return otherKey;
                    }
                }
            }

            /*
             * If we didn't find a binding for the key and there is
             * more than one character accumulated then start checking
             * the largest span of characters from the beginning to
             * see if there is a binding for them.
             *
             * For example if our buffer has ESC,CTRL-M,C the getBound()
             * called previously indicated that there is no binding for
             * this sequence, so this then checks ESC,CTRL-M, and failing
             * that, just ESC. Each keystroke that is pealed off the end
             * during these tests is stuffed onto the pushback buffer so
             * they won't be lost.
             *
             * If there is no binding found, then we go back to waiting for
             * input.
             */
            while (o == null && opBuffer.length() > 0) {
                c = opBuffer.charAt(opBuffer.length() - 1);
                opBuffer.setLength(opBuffer.length() - 1);
                Object o2 = keys.getBound(opBuffer);
                if (o2 instanceof KeyMap) {
                    o = ((KeyMap) o2).getAnotherKey();
                    if (o != null) {
                        pushBackChar.push((char) c);
                    }
                }
            }

        } while (o == null || o instanceof KeyMap);

        return o;
    }

    public String getLastBinding() {
        return opBuffer.toString();
    }

    //
    // Key Bindings
    //

    /**
     * Sets the current keymap by name. Supported keymaps are "emacs",
     * "vi-insert", "vi-move".
     * @param name The name of the keymap to switch to
     * @return true if the keymap was set, or false if the keymap is
     *    not recognized.
     */
    public boolean setKeyMap(String name) {
        return consoleKeys.setKeyMap(name);
    }

    /**
     * Returns the name of the current key mapping.
     * @return the name of the key mapping. This will be the canonical name
     *   of the current mode of the key map and may not reflect the name that
     *   was used with {@link #setKeyMap(String)}.
     */
    public String getKeyMap() {
        return consoleKeys.getKeys().getName();
    }

    protected void viBeginningOfLineOrArgDigit() {
        if (repeatCount > 0) {
            viArgDigit();
        } else {
            beginningOfLine();
        }
    }

    protected void viArgDigit() {
        repeatCount = (repeatCount * 10) + opBuffer.charAt(0) - '0';
        isArgDigit = true;
    }

    protected void viDeleteTo() {
        // This is a weird special case. In vi
        // "dd" deletes the current line. So if we
        // get a delete-to, followed by a delete-to,
        // we delete the line.
        if (state == State.VI_DELETE_TO) {
            killWholeLine();
            state = previousState = State.NORMAL;
        } else {
            state = State.VI_DELETE_TO;
        }
    }

    protected void viYankTo() {
        // Similar to delete-to, a "yy" yanks the whole line.
        if (state == State.VI_YANK_TO) {
            yankBuffer = buf.buffer.toString();
            state = previousState = State.NORMAL;
        } else {
            state = State.VI_YANK_TO;
        }
    }

    protected void viChangeTo() {
        if (state == State.VI_CHANGE_TO) {
            killWholeLine();
            state = previousState = State.NORMAL;
            setKeyMap(KeyMap.VI_INSERT);
        } else {
            state = State.VI_CHANGE_TO;
        }
    }

    protected void cleanup() {
        endOfLine();
        post = null;
        redisplay();
        println();
        flush();
        history.moveToEnd();
    }

    protected void viEofMaybe() {
        /*
         * Handler for CTRL-D. Attempts to follow readline
         * behavior. If the line is empty, then it is an EOF
         * otherwise it is as if the user hit enter.
         */
        if (buf.buffer.length() == 0) {
            state = State.EOF;
        } else {
            acceptLine();
        }
    }

    protected void forwardSearchHistory() {
        originalBuffer = new CursorBuffer();
        originalBuffer.write(buf.buffer);
        originalBuffer.cursor = buf.cursor;
        if (searchTerm != null) {
            previousSearchTerm = searchTerm.toString();
        }
        searchTerm = new StringBuffer(buf.buffer);
        state = State.FORWARD_SEARCH;
        if (searchTerm.length() > 0) {
            searchIndex = searchForwards(searchTerm.toString());
            if (searchIndex == -1) {
                beep();
            }
            printForwardSearchStatus(searchTerm.toString(),
                    searchIndex > -1 ? history.get(searchIndex).toString() : "");
        } else {
            searchIndex = -1;
            printForwardSearchStatus("", "");
        }
    }

    protected void reverseSearchHistory() {
        originalBuffer = new CursorBuffer();
        originalBuffer.write(buf.buffer);
        originalBuffer.cursor = buf.cursor;
        if (searchTerm != null) {
            previousSearchTerm = searchTerm.toString();
        }
        searchTerm = new StringBuffer(buf.buffer);
        state = State.SEARCH;
        if (searchTerm.length() > 0) {
            searchIndex = searchBackwards(searchTerm.toString());
            if (searchIndex == -1) {
                beep();
            }
            printSearchStatus(searchTerm.toString(),
                    searchIndex > -1 ? history.get(searchIndex).toString() : "");
        } else {
            searchIndex = -1;
            printSearchStatus("", "");
        }
    }

    protected void historySearchForward() {
        searchTerm = new StringBuffer(buf.upToCursor());
        int index = history.index() + 1;

        if (index == history.size()) {
            history.moveToEnd();
            setBufferKeepPos(searchTerm.toString());
        } else if (index < history.size()) {
            searchIndex = searchForwards(searchTerm.toString(), index, true);
            if (searchIndex == -1) {
                beep();
            } else {
                // Maintain cursor position while searching.
                if (history.moveTo(searchIndex)) {
                    setBufferKeepPos(history.current());
                } else {
                    beep();
                }
            }
        }
    }

    protected void historySearchBackward() {
        searchTerm = new StringBuffer(buf.upToCursor());
        searchIndex = searchBackwards(searchTerm.toString(), history.index(), true);

        if (searchIndex == -1) {
            beep();
        } else {
            // Maintain cursor position while searching.
            if (history.moveTo(searchIndex)) {
                setBufferKeepPos(history.current());
            } else {
                beep();
            }
        }
    }

    protected void interrupt() {
        state = State.INTERRUPT;
    }

    protected void exitOrDeleteChar() {
        if (buf.buffer.length() == 0) {
            state = State.EOF;
        } else {
            deleteChar();
        }
    }

    protected void quit() {
        getCursorBuffer().clear();
        acceptLine();
    }

    protected void viMoveAcceptLine() {
        /*
         * VI_MOVE_ACCEPT_LINE is the result of an ENTER
         * while in move mode. This is the same as a normal
         * ACCEPT_LINE, except that we need to enter
         * insert mode as well.
         */
        setKeyMap(KeyMap.VI_INSERT);
        acceptLine();
    }

    protected void acceptLine() {
        state = State.DONE;
    }

    protected void selfInsert() {
        putString(opBuffer);
    }

    protected void overwriteMode() {
        buf.setOverTyping(!buf.isOverTyping());
    }

    protected void previousHistory() {
        if (!moveHistory(false)) {
            beep();
        }
    }

    protected void viPreviousHistory() {
        /*
         * According to bash/readline move through history
         * in "vi" mode will move the cursor to the
         * start of the line. If there is no previous
         * history, then the cursor doesn't move.
         */
        if (moveHistory(false, count)) {
            beginningOfLine();
        } else {
            beep();
        }
    }

    protected void nextHistory() {
        if (!moveHistory(true)) {
            beep();
        }
    }

    protected void viNextHistory() {
        /*
         * According to bash/readline move through history
         * in "vi" mode will move the cursor to the
         * start of the line. If there is no next history,
         * then the cursor doesn't move.
         */
        if (moveHistory(true, count)) {
            beginningOfLine();
        } else {
            beep();
        }
    }

    protected void beginningOfHistory() {
        if (history.moveToFirst()) {
            setBuffer(history.current());
        } else {
            beep();
        }
    }

    protected void endOfHistory() {
        if (history.moveToLast()) {
            setBuffer(history.current());
        } else {
            beep();
        }
    }

    protected void viMovementMode() {
        // If we are re-entering move mode from an
        // aborted yank-to, delete-to, change-to then
        // don't move the cursor back. The cursor is
        // only move on an explicit entry to movement
        // mode.
        if (state == State.NORMAL) {
            moveBufferCursor(-1);
        }
        setKeyMap(KeyMap.VI_MOVE);
    }

    protected void viInsertionMode() {
        setKeyMap(KeyMap.VI_INSERT);
    }

    protected void viAppendMode() {
        moveBufferCursor(1);
        setKeyMap(KeyMap.VI_INSERT);
    }

    protected void viAppendEol() {
        endOfLine();
        setKeyMap(KeyMap.VI_INSERT);
    }

    protected void emacsEditingMode() {
        setKeyMap(KeyMap.EMACS);
    }

    protected void viChangeToEol() {
        viDeleteTo(buf.cursor, buf.buffer.length(), true);
        consoleKeys.setKeyMap(KeyMap.VI_INSERT);
    }

    protected void viDeleteToEol() {
        viDeleteTo(buf.cursor, buf.buffer.length(), false);
    }

    protected void quotedInsert() {
        quotedInsert = true;
    }

    protected void viCharSearch() {
        int c = opBuffer.charAt(0);
        int searchChar = (c != ';' && c != ',')
                ? (pushBackChar.isEmpty()
                ? readCharacter()
                : pushBackChar.pop())
                : 0;

        if (!doViCharSearch(count, c, searchChar)) {
            beep();
        }
    }

    protected void viKillWholeLine() {
        killWholeLine();
        setKeyMap(KeyMap.VI_INSERT);
    }

    protected void viInsertBeg() {
        beginningOfLine();
        setKeyMap(KeyMap.VI_INSERT);
    }

    protected void backwardDeleteChar() {
        if (!backspace()) {
            beep();
        }
    }

    protected void viEditingMode() {
        setKeyMap(KeyMap.VI_INSERT);
    }

    protected void callLastKbdMacro() {
        for (int i = 0; i < macro.length(); i++) {
            pushBackChar.push(macro.charAt(macro.length() - 1 - i));
        }
        opBuffer.setLength(0);
    }

    protected void endKbdMacro() {
        recording = false;
        macro = macro.substring(0, macro.length() - opBuffer.length());
    }

    protected void startKbdMacro() {
        recording = true;
    }

    protected void reReadInitFile() {
        consoleKeys.loadKeys(appName, inputrc);
    }

    protected void tabInsert() {
        putString("\t");
    }

    protected void viFirstPrint() {
        beginningOfLine();
        if (!doViNextWord(1)) {
            beep();
        }
    }

    protected void beginningOfLine() {
        setCursorPosition(0);
    }

    protected void endOfLine() {
        moveBufferCursor(buf.length() - buf.cursor);
    }

    protected void deleteChar() {
        if (!deleteCurrentCharacter()) {
            beep();
        }
    }

    /**
     * Deletes the previous character from the cursor position
     */
    protected void viRubout() {
        for (int i = 0; i < count; i++) {
            if (!backspace()) {
                beep();
                break;
            }
        }
    }

    /**
     * Deletes the character you are sitting on and sucks the rest of
     * the line in from the right.
     */
    protected void viDelete() {
        for (int i = 0; i < count; i++) {
            if (!deleteCurrentCharacter()) {
                beep();
                break;
            }
        }
    }

    /**
     * Switches the case of the current character from upper to lower
     * or lower to upper as necessary and advances the cursor one
     * position to the right.
     */
    protected void viChangeCase() {
        for (int i = 0; i < count; i++) {
            if (buf.cursor < buf.buffer.length()) {
                char ch = buf.buffer.charAt(buf.cursor);
                if (Character.isUpperCase(ch)) {
                    ch = Character.toLowerCase(ch);
                }
                else if (Character.isLowerCase(ch)) {
                    ch = Character.toUpperCase(ch);
                }
                buf.buffer.setCharAt(buf.cursor, ch);
                moveBufferCursor(1);
            } else {
                beep();
                break;
            }
        }
    }

    /**
     * Implements the vi change character command (in move-mode "r"
     * followed by the character to change to).
     */
    protected void viChangeChar() {
        int c = pushBackChar.isEmpty() ? readCharacter() : pushBackChar.pop();
        // EOF, ESC, or CTRL-C aborts.
        if (c < 0 || c == '\033' || c == '\003') {
            return;
        }

        for (int i = 0; i < count; i++) {
            if (buf.cursor < buf.buffer.length()) {
                buf.buffer.setCharAt(buf.cursor, (char) c);
                if (i < (count-1)) {
                    moveBufferCursor(1);
                }
            } else {
                beep();
                break;
            }
        }
    }

    /**
     * This is a close facsimile of the actual vi previous word logic. In
     * actual vi words are determined by boundaries of identity characterse.
     * This logic is a bit more simple and simply looks at white space or
     * digits or characters.  It should be revised at some point.
     */
    protected void viPreviousWord() {
        if (buf.cursor == 0) {
            beep();
            return;
        }

        int pos = buf.cursor - 1;
        for (int i = 0; pos > 0 && i < count; i++) {
            // If we are on white space, then move back.
            while (pos > 0 && isWhitespace(buf.buffer.charAt(pos))) {
                --pos;
            }

            while (pos > 0 && !isDelimiter(buf.buffer.charAt(pos-1))) {
                --pos;
            }

            if (pos > 0 && i < (count-1)) {
                --pos;
            }
        }
        setCursorPosition(pos);
    }

    /**
     * Performs the vi "delete-to" action, deleting characters between a given
     * span of the input line.
     * @param startPos The start position
     * @param endPos The end position.
     * @param isChange If true, then the delete is part of a change operationg
     *    (e.g. "c$" is change-to-end-of line, so we first must delete to end
     *    of line to start the change
     * @return true if it succeeded, false otherwise
     */
    protected boolean viDeleteTo(int startPos, int endPos, boolean isChange) {
        if (startPos == endPos) {
            return true;
        }

        if (endPos < startPos) {
            int tmp = endPos;
            endPos = startPos;
            startPos = tmp;
        }

        setCursorPosition(startPos);
        buf.cursor = startPos;
        buf.buffer.delete(startPos, endPos);

        // If we are doing a delete operation (e.g. "d$") then don't leave the
        // cursor dangling off the end. In reality the "isChange" flag is silly
        // what is really happening is that if we are in "move-mode" then the
        // cursor can't be moved off the end of the line, but in "edit-mode" it
        // is ok, but I have no easy way of knowing which mode we are in.
        if (! isChange && startPos > 0 && startPos == buf.length()) {
            moveBufferCursor(-1);
        }
        return true;
    }

    /**
     * Implement the "vi" yank-to operation.  This operation allows you
     * to yank the contents of the current line based upon a move operation,
     * for exaple "yw" yanks the current word, "3yw" yanks 3 words, etc.
     *
     * @param startPos The starting position from which to yank
     * @param endPos The ending position to which to yank
     * @return true if the yank succeeded
     */
    protected boolean viYankTo(int startPos, int endPos) {
        int cursorPos = startPos;

        if (endPos < startPos) {
            int tmp = endPos;
            endPos = startPos;
            startPos = tmp;
        }

        if (startPos == endPos) {
            yankBuffer = "";
            return true;
        }

        yankBuffer = buf.buffer.substring(startPos, endPos);

        /*
         * It was a movement command that moved the cursor to find the
         * end position, so put the cursor back where it started.
         */
        setCursorPosition(cursorPos);
        return true;
    }

    /**
     * Pasts the yank buffer to the right of the current cursor position
     * and moves the cursor to the end of the pasted region.
     */
    protected void viPut() {
        if (yankBuffer.length () != 0) {
            if (buf.cursor < buf.buffer.length()) {
                moveBufferCursor(1);
            }
            for (int i = 0; i < count; i++) {
                putString(yankBuffer);
            }
            moveBufferCursor(-1);
        }
    }

    protected Map<Operation, Widget> createDispatcher() {
        Map<Operation, Widget> dispatcher = new HashMap<>();
        dispatcher.put(Operation.PASTE_FROM_CLIPBOARD, ConsoleReaderImpl::paste);
        dispatcher.put(Operation.BACKWARD_KILL_WORD, ConsoleReaderImpl::deletePreviousWord);
        dispatcher.put(Operation.KILL_WORD, ConsoleReaderImpl::deleteNextWord);
        dispatcher.put(Operation.TRANSPOSE_CHARS, ConsoleReaderImpl::transposeChars);
        dispatcher.put(Operation.INSERT_CLOSE_CURLY, ConsoleReaderImpl::insertCloseCurly);
        dispatcher.put(Operation.INSERT_CLOSE_PAREN, ConsoleReaderImpl::insertCloseParen);
        dispatcher.put(Operation.INSERT_CLOSE_SQUARE, ConsoleReaderImpl::insertCloseSquare);
        dispatcher.put(Operation.CLEAR_SCREEN, ConsoleReaderImpl::clearScreen);
        dispatcher.put(Operation.VI_MATCH, ConsoleReaderImpl::viMatch);
        dispatcher.put(Operation.VI_FIRST_PRINT, ConsoleReaderImpl::viFirstPrint);
        dispatcher.put(Operation.VI_PREV_WORD, ConsoleReaderImpl::viPreviousWord);
        dispatcher.put(Operation.VI_NEXT_WORD, ConsoleReaderImpl::viNextWord);
        dispatcher.put(Operation.VI_END_WORD, ConsoleReaderImpl::viEndWord);
        dispatcher.put(Operation.VI_RUBOUT, ConsoleReaderImpl::viRubout);
        dispatcher.put(Operation.VI_DELETE, ConsoleReaderImpl::viDelete);
        dispatcher.put(Operation.VI_PUT, ConsoleReaderImpl::viPut);
        dispatcher.put(Operation.VI_CHANGE_CASE, ConsoleReaderImpl::viChangeCase);
        dispatcher.put(Operation.PASTE_FROM_CLIPBOARD, ConsoleReaderImpl::paste);
        dispatcher.put(Operation.CAPITALIZE_WORD, ConsoleReaderImpl::capitalizeWord);
        dispatcher.put(Operation.UPCASE_WORD, ConsoleReaderImpl::upCaseWord);
        dispatcher.put(Operation.DOWNCASE_WORD, ConsoleReaderImpl::downCaseWord);
        dispatcher.put(Operation.END_OF_LINE, ConsoleReaderImpl::endOfLine);
        dispatcher.put(Operation.DELETE_CHAR, ConsoleReaderImpl::deleteChar);
        dispatcher.put(Operation.BACKWARD_CHAR, ConsoleReaderImpl::backwardChar);
        dispatcher.put(Operation.FORWARD_CHAR, ConsoleReaderImpl::forwardChar);
        dispatcher.put(Operation.UNIX_LINE_DISCARD, ConsoleReaderImpl::resetLine);
        dispatcher.put(Operation.UNIX_WORD_RUBOUT, ConsoleReaderImpl::unixWordRubout);
        dispatcher.put(Operation.POSSIBLE_COMPLETIONS, ConsoleReaderImpl::printCompletionCandidates);
        dispatcher.put(Operation.BEGINNING_OF_LINE, ConsoleReaderImpl::beginningOfLine);
        dispatcher.put(Operation.YANK, ConsoleReaderImpl::yank);
        dispatcher.put(Operation.YANK_POP, ConsoleReaderImpl::yankPop);
        dispatcher.put(Operation.KILL_LINE, ConsoleReaderImpl::killLine);
        dispatcher.put(Operation.KILL_WHOLE_LINE, ConsoleReaderImpl::killWholeLine);
        dispatcher.put(Operation.BACKWARD_WORD, ConsoleReaderImpl::backwardWord);
        dispatcher.put(Operation.FORWARD_WORD, ConsoleReaderImpl::forwardWord);
        dispatcher.put(Operation.COMPLETE, ConsoleReaderImpl::complete);
        dispatcher.put(Operation.PREVIOUS_HISTORY, ConsoleReaderImpl::previousHistory);
        dispatcher.put(Operation.VI_PREVIOUS_HISTORY, ConsoleReaderImpl::viPreviousHistory);
        dispatcher.put(Operation.NEXT_HISTORY, ConsoleReaderImpl::nextHistory);
        dispatcher.put(Operation.VI_NEXT_HISTORY, ConsoleReaderImpl::viNextHistory);
        dispatcher.put(Operation.BACKWARD_DELETE_CHAR, ConsoleReaderImpl::backwardDeleteChar);
        dispatcher.put(Operation.BEGINNING_OF_HISTORY, ConsoleReaderImpl::beginningOfHistory);
        dispatcher.put(Operation.END_OF_HISTORY, ConsoleReaderImpl::endOfHistory);
        dispatcher.put(Operation.OVERWRITE_MODE, ConsoleReaderImpl::overwriteMode);
        dispatcher.put(Operation.SELF_INSERT, ConsoleReaderImpl::selfInsert);
        dispatcher.put(Operation.TAB_INSERT, ConsoleReaderImpl::tabInsert);
        dispatcher.put(Operation.RE_READ_INIT_FILE, ConsoleReaderImpl::reReadInitFile);
        dispatcher.put(Operation.START_KBD_MACRO, ConsoleReaderImpl::startKbdMacro);
        dispatcher.put(Operation.END_KBD_MACRO, ConsoleReaderImpl::endKbdMacro);
        dispatcher.put(Operation.CALL_LAST_KBD_MACRO, ConsoleReaderImpl::callLastKbdMacro);
        dispatcher.put(Operation.VI_EDITING_MODE, ConsoleReaderImpl::viEditingMode);
        dispatcher.put(Operation.VI_MOVEMENT_MODE, ConsoleReaderImpl::viMovementMode);
        dispatcher.put(Operation.VI_INSERTION_MODE, ConsoleReaderImpl::viInsertionMode);
        dispatcher.put(Operation.VI_APPEND_MODE, ConsoleReaderImpl::viAppendMode);
        dispatcher.put(Operation.VI_APPEND_EOL, ConsoleReaderImpl::viAppendEol);
        dispatcher.put(Operation.VI_SEARCH, ConsoleReaderImpl::viSearch);
        dispatcher.put(Operation.VI_INSERT_BEG, ConsoleReaderImpl::viInsertBeg);
        dispatcher.put(Operation.VI_KILL_WHOLE_LINE, ConsoleReaderImpl::viKillWholeLine);
        dispatcher.put(Operation.VI_CHAR_SEARCH, ConsoleReaderImpl::viCharSearch);
        dispatcher.put(Operation.VI_CHANGE_CHAR, ConsoleReaderImpl::viChangeChar);
        dispatcher.put(Operation.QUOTED_INSERT, ConsoleReaderImpl::quotedInsert);
        dispatcher.put(Operation.VI_DELETE_TO_EOL, ConsoleReaderImpl::viDeleteToEol);
        dispatcher.put(Operation.VI_CHANGE_TO_EOL, ConsoleReaderImpl::viChangeToEol);
        dispatcher.put(Operation.EMACS_EDITING_MODE, ConsoleReaderImpl::emacsEditingMode);
        dispatcher.put(Operation.ACCEPT_LINE, ConsoleReaderImpl::acceptLine);
        dispatcher.put(Operation.INSERT_COMMENT, ConsoleReaderImpl::insertComment);
        dispatcher.put(Operation.VI_INSERT_COMMENT, ConsoleReaderImpl::viInsertComment);
        dispatcher.put(Operation.VI_MOVE_ACCEPT_LINE, ConsoleReaderImpl::viMoveAcceptLine);
        dispatcher.put(Operation.QUIT, ConsoleReaderImpl::quit);
        dispatcher.put(Operation.ABORT, ConsoleReaderImpl::abort);
        dispatcher.put(Operation.INTERRUPT, ConsoleReaderImpl::interrupt);
        dispatcher.put(Operation.EXIT_OR_DELETE_CHAR, ConsoleReaderImpl::exitOrDeleteChar);
        dispatcher.put(Operation.HISTORY_SEARCH_BACKWARD, ConsoleReaderImpl::historySearchBackward);
        dispatcher.put(Operation.HISTORY_SEARCH_FORWARD, ConsoleReaderImpl::historySearchForward);
        dispatcher.put(Operation.REVERSE_SEARCH_HISTORY, ConsoleReaderImpl::reverseSearchHistory);
        dispatcher.put(Operation.FORWARD_SEARCH_HISTORY, ConsoleReaderImpl::forwardSearchHistory);
        dispatcher.put(Operation.VI_EOF_MAYBE, ConsoleReaderImpl::viEofMaybe);
        dispatcher.put(Operation.VI_DELETE_TO, ConsoleReaderImpl::viDeleteTo);
        dispatcher.put(Operation.VI_YANK_TO, ConsoleReaderImpl::viYankTo);
        dispatcher.put(Operation.VI_CHANGE_TO, ConsoleReaderImpl::viChangeTo);
        dispatcher.put(Operation.VI_ARG_DIGIT, ConsoleReaderImpl::viArgDigit);
        dispatcher.put(Operation.VI_BEGINNING_OF_LINE_OR_ARG_DIGIT, ConsoleReaderImpl::viBeginningOfLineOrArgDigit);
        return dispatcher;
    }

    protected void redisplay() {
        String buffer = buf.toString();
        if (mask != null) {
            if (mask == NULL_MASK) {
                buffer = "";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = buffer.length(); i-- > 0;) {
                    sb.append((char) mask);
                }
                buffer = sb.toString();
            }
        } else if (highlighter != null) {
            buffer = highlighter.highlight(buffer);
        }

        String oldPostStr = "";
        String newPostStr = "";
        if (oldPost != null) {
            oldPostStr = "\n" + toColumns(oldPost, oldColumns);
        }
        if (post != null) {
            newPostStr = "\n" + toColumns(post, size.getColumns());
        }
        List<String> oldLines = AnsiHelper.splitLines(oldPrompt + oldBuf + oldPostStr, oldColumns, TAB_WIDTH);
        List<String> newLines = AnsiHelper.splitLines(prompt + buffer + newPostStr, size.getColumns(), TAB_WIDTH);

        int lineIndex = 0;
        int currentPos = 0;
        while (lineIndex < Math.min(oldLines.size(), newLines.size())) {
            String oldLine = oldLines.get(lineIndex);
            String newLine = newLines.get(lineIndex);
            lineIndex++;

            LinkedList<DiffHelper.Diff> diffs = DiffHelper.diff(oldLine, newLine);
            boolean ident = true;
            boolean cleared = false;
            int curCol = currentPos;
            for (int i = 0; i < diffs.size(); i++) {
                DiffHelper.Diff diff = diffs.get(i);
                int width = wcwidth(AnsiHelper.strip(diff.text), currentPos);
                switch (diff.operation) {
                    case EQUAL:
                        if (!ident) {
                            cursorPos = moveVisualCursorTo(currentPos);
                            rawPrint(diff.text);
                            cursorPos += width;
                            currentPos = cursorPos;
                        } else {
                            currentPos += width;
                        }
                        break;
                    case INSERT:
                        if (i <= diffs.size() - 2
                                && diffs.get(i+1).operation == DiffHelper.Operation.EQUAL) {
                            cursorPos = moveVisualCursorTo(currentPos);
                            boolean hasIch = console.getStringCapability(Capability.parm_ich) != null;
                            boolean hasIch1 = console.getStringCapability(Capability.insert_character) != null;
                            if (hasIch) {
                                console.puts(Capability.parm_ich, width);
                                rawPrint(diff.text);
                                cursorPos += width;
                                currentPos = cursorPos;
                                break;
                            } else if (hasIch1) {
                                for (int j = 0; j < width; j++) {
                                    console.puts(Capability.insert_character);
                                }
                                rawPrint(diff.text);
                                cursorPos += width;
                                currentPos = cursorPos;
                                break;
                            }
                        }
                        moveVisualCursorTo(currentPos);
                        rawPrint(diff.text);
                        cursorPos += width;
                        currentPos = cursorPos;
                        ident = false;
                        break;
                    case DELETE:
                        if (cleared) {
                            continue;
                        }
                        if (currentPos - curCol >= size.getColumns()) {
                            continue;
                        }
                        if (i <= diffs.size() - 2
                                && diffs.get(i+1).operation == DiffHelper.Operation.EQUAL) {
                            if (currentPos + wcwidth(diffs.get(i+1).text, cursorPos) < size.getColumns()) {
                                moveVisualCursorTo(currentPos);
                                boolean hasDch = console.getStringCapability(Capability.parm_dch) != null;
                                boolean hasDch1 = console.getStringCapability(Capability.delete_character) != null;
                                if (hasDch) {
                                    console.puts(Capability.parm_dch, width);
                                    break;
                                } else if (hasDch1) {
                                    for (int j = 0; j < width; j++) {
                                        console.puts(Capability.delete_character);
                                    }
                                    break;
                                }
                            }
                        }
                        int oldLen = wcwidth(oldLine, 0);
                        int newLen = wcwidth(newLine, 0);
                        int nb = Math.max(oldLen, newLen) - currentPos;
                        moveVisualCursorTo(currentPos);
                        if (!console.puts(Capability.clr_eol)) {
                            rawPrint(' ', nb);
                            cursorPos += nb;
                        }
                        cleared = true;
                        ident = false;
                        break;
                }
            }
            if (console.getBooleanCapability(Capability.auto_right_margin)
                    && console.getBooleanCapability(Capability.eat_newline_glitch)
                    && cursorPos > curCol && cursorPos % size.getColumns() == 0) {
                rawPrint(' '); // move cursor to next line by printing dummy space
                console.puts(Capability.carriage_return); // CR / not newline.
            }
            currentPos = curCol + size.getColumns();
        }
        while (lineIndex < Math.max(oldLines.size(), newLines.size())) {
            moveVisualCursorTo(currentPos);
            if (lineIndex < oldLines.size()) {
                if (console.getStringCapability(Capability.clr_eol) != null) {
                    console.puts(Capability.clr_eol);
                } else {
                    int nb = wcwidth(newLines.get(lineIndex), cursorPos);
                    rawPrint(' ', nb);
                    cursorPos += nb;
                }
            } else {
                rawPrint(newLines.get(lineIndex));
                cursorPos += wcwidth(newLines.get(lineIndex), cursorPos);
            }
            lineIndex++;
            currentPos = currentPos + size.getColumns();
        }
        int promptLines = AnsiHelper.splitLines(prompt, size.getColumns(), TAB_WIDTH).size();
        moveVisualCursorTo((promptLines - 1) * size.getColumns()
                + promptLen + wcwidth(buf.buffer, 0, buf.cursor, promptLen));
        oldBuf = buffer;
        oldPrompt = prompt;
        oldPost = post;
        oldColumns = size.getColumns();
    }

    //
    // Completion
    //

    protected void complete() {
        // There is an annoyance with tab completion in that
        // sometimes the user is actually pasting input in that
        // has physical tabs in it.  This attempts to look at how
        // quickly a character follows the tab, if the character
        // follows *immediately*, we assume it is a tab literal.
        boolean isTabLiteral = false;
        if (getBoolean(COPY_PASTE_DETECTION, false)
                && opBuffer.toString().equals("\t")
                && (!pushBackChar.isEmpty()
                || peekCharacter(COPY_PASTE_DETECTION_TIMEOUT) != READ_EXPIRED)) {
            isTabLiteral = true;
        } else if (getBoolean(DISABLE_COMPLETION, false)) {
            isTabLiteral = true;
        }
        
        if (!isTabLiteral) {
            if (!doComplete()) {
                beep();
            }
        } else {
            selfInsert();
        }
    }

    /**
     * Use the completers to modify the buffer with the appropriate completions.
     *
     * @return true if successful
     */
    protected boolean doComplete() {
        // debug ("tab for (" + buf + ")");
        if (completers.size() == 0) {
            return false;
        }

        List<String> candidates = new LinkedList<>();
        String bufstr = buf.buffer.toString();
        int cursor = buf.cursor;

        int position = -1;

        for (Completer comp : completers) {
            if ((position = comp.complete(bufstr, cursor, candidates)) != -1) {
                break;
            }
        }

        return candidates.size() != 0 && getCompletionHandler().complete(this, candidates, position);
    }

    protected void printCompletionCandidates() {
        // debug ("tab for (" + buf + ")");
        if (completers.size() == 0) {
            return;
        }

        List<String> candidates = new LinkedList<>();
        String bufstr = buf.buffer.toString();
        int cursor = buf.cursor;

        for (Completer comp : completers) {
            if (comp.complete(bufstr, cursor, candidates) != -1) {
                break;
            }
        }
        printCandidates(candidates);
    }

    /**
     * Used in "vi" mode for argumented history move, to move a specific
     * number of history entries forward or back.
     *
     * @param next If true, move forward
     * @param count The number of entries to move
     * @return true if the move was successful
     */
    protected boolean moveHistory(final boolean next, int count) {
        boolean ok = true;
        for (int i = 0; i < count && (ok = moveHistory(next)); i++) {
            /* empty */
        }
        return ok;
    }

    /**
     * Move up or down the history tree.
     */
    protected boolean moveHistory(final boolean next) {
        if (next && !history.next()) {
            return false;
        }
        else if (!next && !history.previous()) {
            return false;
        }

        setBuffer(history.current());

        return true;
    }

    //
    // Printing
    //

    /**
     * Output the specified characters to the output stream without manipulating the current buffer.
     */
    protected int print(final CharSequence buff, int cursorPos) {
        return print(buff, 0, buff.length(), cursorPos);
    }

    protected int print(final CharSequence buff, int start, int end) {
        return print(buff, start, end, getCursorPosition());
    }

    protected int print(final CharSequence buff, int start, int end, int cursorPos) {
        checkNotNull(buff);
        for (int i = start; i < end; i++) {
            char c = buff.charAt(i);
            if (c == '\t') {
                int nb = nextTabStop(cursorPos);
                cursorPos += nb;
                while (nb-- > 0) {
                    console.writer().write(' ');
                }
            } else if (c < 32) {
                console.writer().write('^');
                console.writer().write((char) (c + '@'));
                cursorPos += 2;
            } else {
                int w = WCWidth.wcwidth(c);
                if (w > 0) {
                    console.writer().write(c);
                    cursorPos += w;
                }
            }
        }
        return cursorPos;
    }

    /**
     * Output the specified string to the output stream (but not the buffer).
     */
    public void print(final CharSequence s) {
        print(s, getCursorPosition());
    }

    public void println(final CharSequence s) {
        print(s);
        println();
    }

    /**
     * Output a platform-dependant newline.
     */
    public void println() {
        console.puts(Capability.carriage_return);
        rawPrint("\n");
        redrawLine();
    }

    /**
     * Raw output printing
     */
    final void rawPrint(final int c) {
        console.writer().write(c);
    }

    final void rawPrint(final String str) {
        for (int i = 0; i < str.length(); i++) {
            rawPrint(str.charAt(i));
        }
    }

    protected void rawPrint(final char c, final int num) {
        for (int i = 0; i < num; i++) {
            rawPrint(c);
        }
    }

    protected void rawPrintln(final String s) {
        rawPrint(s);
        println();
    }


    //
    // Actions
    //

    /**
     * Issue a delete.
     *
     * @return true if successful
     */
    public boolean delete() {
        if (buf.cursor == buf.buffer.length()) {
          return false;
        }

        buf.buffer.delete(buf.cursor, buf.cursor + 1);

        return true;
    }

    protected void killWholeLine() {
        beginningOfLine();
        killLine();
    }

    /**
     * Kill the buffer ahead of the current cursor position.
     *
     * @return true if successful
     */
    public boolean killLine() {
        int cp = buf.cursor;
        int len = buf.buffer.length();

        int num = len - cp;

        char[] killed = new char[num];
        buf.buffer.getChars(cp, (cp + num), killed, 0);
        buf.buffer.delete(cp, (cp + num));

        String copy = new String(killed);
        killRing.add(copy);

        return true;
    }

    public void yank() {
        String yanked = killRing.yank();
        if (yanked == null) {
            beep();
        } else {
            putString(yanked);
        }
    }

    public void yankPop() {
        if (!killRing.lastYank()) {
            beep();
            return;
        }
        String current = killRing.yank();
        if (current == null) {
            // This shouldn't happen.
            beep();
            return;
        }
        backspace(current.length());
        String yanked = killRing.yankPop();
        if (yanked == null) {
            // This shouldn't happen.
            beep();
            return;
        }

        putString(yanked);
    }

    /**
     * Clear the screen by issuing the ANSI "clear screen" code.
     */
    public void clearScreen() {
        if (console.puts(Capability.clear_screen)) {
            redrawLine();
        } else {
            println();
        }
    }

    /**
     * Issue an audible keyboard bell.
     */
    public void beep() {
        int bell_preference = AUDIBLE_BELL;
        String bellStyle = getVariable(BELL_STYLE);
        if ("none".equals(bellStyle) || "off".equals(bellStyle)) {
            bell_preference = NO_BELL;
        } else if ("audible".equals(bellStyle)) {
            bell_preference = AUDIBLE_BELL;
        } else if ("visible".equals(bellStyle)) {
            bell_preference = VISIBLE_BELL;
        } else if ("on".equals(bellStyle)) {
            String preferVisibleBellStr = getVariable(PREFER_VISIBLE_BELL);
            if ("off".equals(preferVisibleBellStr)) {
                bell_preference = AUDIBLE_BELL;
            } else {
                bell_preference = VISIBLE_BELL;
            }
        }
        if (bell_preference == VISIBLE_BELL) {
            if (console.puts(Capability.flash_screen)
                    || console.puts(Capability.bell)) {
                flush();
            }
        } else if (bell_preference == AUDIBLE_BELL) {
            if (console.puts(Capability.bell)) {
                flush();
            }
        }
    }

    /**
     * Paste the contents of the clipboard into the console buffer
     *
     * @return true if clipboard contents pasted
     */
    public boolean paste() {
        Clipboard clipboard;
        try { // May throw ugly exception on system without X
            clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
        }
        catch (Exception e) {
            return false;
        }

        if (clipboard == null) {
            return false;
        }

        Transferable transferable = clipboard.getContents(null);

        if (transferable == null) {
            return false;
        }

        try {
            @SuppressWarnings("deprecation")
            Object content = transferable.getTransferData(DataFlavor.plainTextFlavor);

            // This fix was suggested in bug #1060649 at
            // http://sourceforge.net/tracker/index.php?func=detail&aid=1060649&group_id=64033&atid=506056
            // to get around the deprecated DataFlavor.plainTextFlavor, but it
            // raises a UnsupportedFlavorException on Mac OS X

            if (content == null) {
                try {
                    content = new DataFlavor().getReaderForText(transferable);
                }
                catch (Exception e) {
                    // ignore
                }
            }

            if (content == null) {
                return false;
            }

            String value;

            if (content instanceof java.io.Reader) {
                // TODO: we might want instead connect to the input stream
                // so we can interpret individual lines
                value = "";
                String line;

                BufferedReader read = new BufferedReader((java.io.Reader) content);
                while ((line = read.readLine()) != null) {
                    if (value.length() > 0) {
                        value += "\n";
                    }

                    value += line;
                }
            }
            else {
                value = content.toString();
            }

            if (value == null) {
                return true;
            }

            putString(value);

            return true;
        }
        catch (UnsupportedFlavorException | IOException e) {
            Log.error("Paste failed: ", e);

            return false;
        }
    }

    /**
     * Adding a triggered Action allows to give another curse of action if a character passed the pre-processing.
     * <p/>
     * Say you want to close the application if the user enter q.
     * addTriggerAction('q', new ActionListener(){ System.exit(0); }); would do the trick.
     */
    public void addTriggeredAction(final char c, final Widget widget) {
        getKeys().bind(Character.toString(c), widget);
    }

    //
    // Formatted Output
    //

    /**
     * Print out the candidates. If the size of the candidates is greater than the
     * {@link ConsoleReader#COMPLETION_QUERY_ITEMS}, they prompt with a warning.
     *
     * @param candidates the list of candidates to print
     */
    public void printCandidates(Collection<String> candidates) 
    {
        candidates = new LinkedHashSet<>(candidates);

        int max = getInt(COMPLETION_QUERY_ITEMS, 100);
        if (max > 0 && candidates.size() >= max) {
            println();
            rawPrint(Messages.DISPLAY_CANDIDATES.format(candidates.size()));
            flush();

            int c;

            String noOpt = Messages.DISPLAY_CANDIDATES_NO.format();
            String yesOpt = Messages.DISPLAY_CANDIDATES_YES.format();
            char[] allowed = {yesOpt.charAt(0), noOpt.charAt(0)};

            while ((c = readCharacter(allowed)) != -1) {
                String tmp = new String(new char[]{(char) c});

                if (noOpt.startsWith(tmp)) {
                    println();
                    return;
                }
                else if (yesOpt.startsWith(tmp)) {
                    break;
                }
                else {
                    beep();
                }
            }
            printColumns(candidates);
            println();
        }
        else {
            post = candidates.toArray(new String[candidates.size()]);
        }
    }

    protected String toColumns(String[] items, int width) {
        if (items == null || items.length == 0) {
            return "";
        }
        int maxWidth = 0;
        for (String item : items) {
            // we use 0 here, as we don't really support tabulations inside candidates
            int len = wcwidth(AnsiHelper.strip(item), 0);
            maxWidth = Math.max(maxWidth, len);
        }
        maxWidth = maxWidth + 3;

        StringBuilder buff = new StringBuilder();
        int realLength = 0;
        for (String item : items) {
            if ((realLength + maxWidth) > width) {
                buff.append('\n');
                realLength = 0;
            }

            buff.append(item);
            int strippedItemLength = wcwidth(AnsiHelper.strip(item), 0);
            for (int i = 0; i < (maxWidth - strippedItemLength); i++) {
                buff.append(' ');
            }
            realLength += maxWidth;
        }
        buff.append('\n');
        return buff.toString();
    }

    /**
     * Output the specified {@link Collection} in proper columns.
     */
    public void printColumns(final Collection<? extends CharSequence> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        int width = size.getColumns();
        int height = size.getRows();

        int maxWidth = 0;
        for (CharSequence item : items) {
            // we use 0 here, as we don't really support tabulations inside candidates
            int len = wcwidth(AnsiHelper.strip(item.toString()), 0);
            maxWidth = Math.max(maxWidth, len);
        }
        maxWidth = maxWidth + 3;
        Log.debug("Max width: ", maxWidth);

        int showLines;
        if (getBoolean(PAGE_COMPLETIONS, true)) {
            showLines = height - 1; // page limit
        }
        else {
            showLines = Integer.MAX_VALUE;
        }

        StringBuilder buff = new StringBuilder();
        int realLength = 0;
        for (CharSequence item : items) {
            if ((realLength + maxWidth) > width) {
                rawPrintln(buff.toString());
                buff.setLength(0);
                realLength = 0;

                if (--showLines == 0) {
                    // Overflow
                    String more = Messages.DISPLAY_MORE.format();
                    print(more);
                    flush();
                    int c = readCharacter();
                    if (c == '\r' || c == '\n') {
                        // one step forward
                        showLines = 1;
                    }
                    else if (c != 'q') {
                        // page forward
                        showLines = height - 1;
                    }

                    console.puts(Capability.carriage_return);
                    if (c == 'q') {
                        // cancel
                        break;
                    }
                }
            }

            // NOTE: toString() is important here due to AnsiString being retarded
            buff.append(item.toString());
            int strippedItemLength = wcwidth(AnsiHelper.strip(item.toString()), 0);
            for (int i = 0; i < (maxWidth - strippedItemLength); i++) {
                buff.append(' ');
            }
            realLength += maxWidth;
        }

        if (buff.length() > 0) {
            rawPrintln(buff.toString());
        }
    }

    public void printSearchStatus(String searchTerm, String match) {
        printSearchStatus(searchTerm, match, "bck-i-search");
    }

    public void printForwardSearchStatus(String searchTerm, String match) {
        printSearchStatus(searchTerm, match, "i-search");
    }

    protected void printSearchStatus(String searchTerm, String match, String searchLabel) {
        // Grab the prompt lines but the last one
        post = new String[] { searchLabel + ": " + searchTerm + "_" };
        setBuffer(match);
        buf.cursor = match.indexOf(searchTerm);
    }

    public void restoreLine() {
        setPrompt(originalPrompt);
        this.post = null;
    }

    //
    // History search
    //
    /**
     * Search backward in history from a given position.
     *
     * @param searchTerm substring to search for.
     * @param startIndex the index from which on to search
     * @return index where this substring has been found, or -1 else.
     */
    public int searchBackwards(String searchTerm, int startIndex) {
        return searchBackwards(searchTerm, startIndex, false);
    }

    /**
     * Search backwards in history from the current position.
     *
     * @param searchTerm substring to search for.
     * @return index where the substring has been found, or -1 else.
     */
    public int searchBackwards(String searchTerm) {
        return searchBackwards(searchTerm, history.index());
    }


    public int searchBackwards(String searchTerm, int startIndex, boolean startsWith) {
        ListIterator<History.Entry> it = history.entries(startIndex);
        while (it.hasPrevious()) {
            History.Entry e = it.previous();
            if (startsWith) {
                if (e.value().toString().startsWith(searchTerm)) {
                    return e.index();
                }
            } else {
                if (e.value().toString().contains(searchTerm)) {
                    return e.index();
                }
            }
        }
        return -1;
    }

    /**
     * Search forward in history from a given position.
     *
     * @param searchTerm substring to search for.
     * @param startIndex the index from which on to search
     * @return index where this substring has been found, or -1 else.
     */
    public int searchForwards(String searchTerm, int startIndex) {
        return searchForwards(searchTerm, startIndex, false);
    }
    /**
     * Search forwards in history from the current position.
     *
     * @param searchTerm substring to search for.
     * @return index where the substring has been found, or -1 else.
     */
    public int searchForwards(String searchTerm) {
        return searchForwards(searchTerm, history.index());
    }

    public int searchForwards(String searchTerm, int startIndex, boolean startsWith) {
        if (startIndex >= history.size()) {
            startIndex = history.size() - 1;
        }

        ListIterator<History.Entry> it = history.entries(startIndex);

        if (searchIndex != -1 && it.hasNext()) {
            it.next();
        }

        while (it.hasNext()) {
            History.Entry e = it.next();
            if (startsWith) {
                if (e.value().toString().startsWith(searchTerm)) {
                    return e.index();
                }
            } else {
                if (e.value().toString().contains(searchTerm)) {
                    return e.index();
                }
            }
        }
        return -1;
    }

    //
    // Helpers
    //

    /**
     * Checks to see if the specified character is a delimiter. We consider a
     * character a delimiter if it is anything but a letter or digit.
     *
     * @param c     The character to test
     * @return      True if it is a delimiter
     */
    protected boolean isDelimiter(final char c) {
        return !Character.isLetterOrDigit(c);
    }

    /**
     * Checks to see if a character is a whitespace character. Currently
     * this delegates to {@link Character#isWhitespace(char)}, however
     * eventually it should be hooked up so that the definition of whitespace
     * can be configured, as readline does.
     *
     * @param c The character to check
     * @return true if the character is a whitespace
     */
    protected boolean isWhitespace(final char c) {
        return Character.isWhitespace(c);
    }

    public String getVariable(String name) {
        String v = variables.get(name);
        return v != null ? v : consoleKeys.getVariable(name);
    }

    public void setVariable(String name, String value) {
        variables.put(name, value);
    }

    boolean getBoolean(String name, boolean def) {
        String v = getVariable(name);
        return v != null ? v.isEmpty() || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("1") : def;
    }

    int getInt(String name, int def) {
        int nb = def;
        String v = getVariable(name);
        if (v != null) {
            nb = 0;
            try {
                nb = Integer.parseInt(v);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return nb;
    }

    long getLong(String name, long def) {
        long nb = def;
        String v = getVariable(name);
        if (v != null) {
            nb = 0;
            try {
                nb = Long.parseLong(v);
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return nb;
    }

    String getString(String name, String def) {
        String v = getVariable(name);
        return (v != null) ? v : def;
    }

}