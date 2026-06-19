import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final List<Job> jobs = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        while (true) {
            reapCompletedJobsBeforePrompt();
            System.out.print("$ ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String cmd = scanner.nextLine();
            List<String> argv = parseCommand(cmd);
            if (argv.isEmpty()) {
                continue;
            }

            boolean background = false;
            if (argv.size() > 0 && "&".equals(argv.get(argv.size() - 1))) {
                background = true;
                argv.remove(argv.size() - 1);
            }

            if (argv.contains("|")) {
                // split into multiple pipeline segments
                List<List<String>> segments = new ArrayList<>();
                List<String> current = new ArrayList<>();
                for (String t : argv) {
                    if ("|".equals(t)) {
                        if (!current.isEmpty()) {
                            segments.add(new ArrayList<>(current));
                            current.clear();
                        }
                    } else {
                        current.add(t);
                    }
                }
                if (!current.isEmpty())
                    segments.add(new ArrayList<>(current));
                if (segments.size() < 2)
                    continue;
                runMultiPipeline(segments);
                continue;
            }

            String outputRedirectTarget = null;
            boolean outputAppend = false;
            String errorRedirectTarget = null;
            boolean errorAppend = false;
            List<String> cleanArgv = new ArrayList<>();
            for (int i = 0; i < argv.size(); i++) {
                String token = argv.get(i);
                if ((token.equals(">") || token.equals("1>")) && i + 1 < argv.size() && outputRedirectTarget == null) {
                    outputRedirectTarget = argv.get(i + 1);
                    outputAppend = false;
                    i++;
                } else if ((token.equals(">>") || token.equals("1>>")) && i + 1 < argv.size()
                        && outputRedirectTarget == null) {
                    outputRedirectTarget = argv.get(i + 1);
                    outputAppend = true;
                    i++;
                } else if (token.equals("2>") && i + 1 < argv.size() && errorRedirectTarget == null) {
                    errorRedirectTarget = argv.get(i + 1);
                    errorAppend = false;
                    i++;
                } else if (token.equals("2>>") && i + 1 < argv.size() && errorRedirectTarget == null) {
                    errorRedirectTarget = argv.get(i + 1);
                    errorAppend = true;
                    i++;
                } else {
                    cleanArgv.add(token);
                }
            }

            if (cleanArgv.isEmpty()) {
                continue;
            }

            String commandName = cleanArgv.get(0);
            if (commandName.equals("exit")) {
                break;
            }

            File outputFile = null;
            if (outputRedirectTarget != null) {
                outputFile = new File(outputRedirectTarget);
                if (!outputFile.isAbsolute()) {
                    outputFile = new File(System.getProperty("user.dir"), outputRedirectTarget);
                }
            }
            File errorFile = null;
            if (errorRedirectTarget != null) {
                errorFile = new File(errorRedirectTarget);
                if (!errorFile.isAbsolute()) {
                    errorFile = new File(System.getProperty("user.dir"), errorRedirectTarget);
                }
                try (FileOutputStream ignored = new FileOutputStream(errorFile, errorAppend)) {
                }
            }

            if (commandName.equals("pwd")) {
                if (outputFile != null) {
                    try (PrintStream ps = new PrintStream(new FileOutputStream(outputFile, outputAppend))) {
                        ps.println(System.getProperty("user.dir"));
                    }
                } else {
                    System.out.println(System.getProperty("user.dir"));
                }
            } else if (commandName.equals("cd")) {
                String target = argv.size() > 1 ? argv.get(1) : "";
                File targetDir;
                if (target.equals("~")) {
                    String home = System.getenv("HOME");
                    targetDir = home != null ? new File(home) : new File("~");
                } else if (target.startsWith("/")) {
                    targetDir = new File(target);
                } else {
                    targetDir = new File(System.getProperty("user.dir"), target);
                }
                try {
                    File canonical = targetDir.getCanonicalFile();
                    if (canonical.isDirectory()) {
                        System.setProperty("user.dir", canonical.getAbsolutePath());
                    } else {
                        System.out.println("cd: " + target + ": No such file or directory");
                    }
                } catch (Exception e) {
                    System.out.println("cd: " + target + ": No such file or directory");
                }
            } else if (commandName.equals("echo")) {
                if (cleanArgv.size() > 1) {
                    String output = String.join(" ", cleanArgv.subList(1, cleanArgv.size()));
                    if (outputFile != null) {
                        try (PrintStream ps = new PrintStream(new FileOutputStream(outputFile, outputAppend))) {
                            ps.println(output);
                        }
                    } else {
                        System.out.println(output);
                    }
                } else {
                    if (outputFile != null) {
                        try (PrintStream ps = new PrintStream(new FileOutputStream(outputFile, outputAppend))) {
                            ps.println();
                        }
                    } else {
                        System.out.println();
                    }
                }
            } else if (commandName.equals("type")) {
                String target = argv.size() > 1 ? argv.get(1) : "";
                if (isBuiltin(target)) {
                    System.out.println(target + " is a shell builtin");
                } else {
                    String pathResult = findExecutableInPath(target);
                    if (pathResult != null) {
                        System.out.println(target + " is " + pathResult);
                    } else {
                        System.out.println(target + ": not found");
                    }
                }
            } else if (commandName.equals("jobs")) {
                printJobsBuiltin();
            } else {
                String pathResult = findExecutableInPath(commandName);
                if (pathResult != null) {
                    String[] argsForProcess = cleanArgv.size() > 1
                            ? cleanArgv.subList(1, cleanArgv.size()).toArray(new String[0])
                            : new String[0];
                    String commandString = String.join(" ", cleanArgv);
                    runExternalCommand(pathResult, commandName, argsForProcess, outputFile, errorFile, outputAppend,
                            errorAppend, background, commandString);
                } else {
                    System.out.println(commandName + ": command not found");
                }
            }
        }
    }

    private static void runExternalCommand(String executablePath, String commandName, String[] args, File outputFile,
            File errorFile, boolean outputAppend, boolean errorAppend, boolean background, String commandString)
            throws Exception {
        ProcessBuilder processBuilder;
        if (background) {
            List<String> command = new ArrayList<>();
            command.add(executablePath);
            command.addAll(Arrays.asList(args));
            processBuilder = new ProcessBuilder(command);
        } else {
            String shellCmd = "exec -a \"$0\" \"$1\" \"${@:2}\"";
            List<String> command = new ArrayList<>();
            command.add("/bin/bash");
            command.add("-c");
            command.add(shellCmd);
            command.add(commandName);
            command.add(executablePath);
            command.addAll(Arrays.asList(args));
            processBuilder = new ProcessBuilder(command);
        }
        processBuilder.directory(new File(System.getProperty("user.dir")));
        if (outputFile != null) {
            if (outputAppend) {
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(outputFile));
            } else {
                processBuilder.redirectOutput(outputFile);
            }
        } else {
            processBuilder.inheritIO();
        }
        if (errorFile != null) {
            if (errorAppend) {
                processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(errorFile));
            } else {
                processBuilder.redirectError(errorFile);
            }
        } else {
            processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        }
        Process process = processBuilder.start();
        if (background) {
            int jobId = nextJobId();
            jobs.add(new Job(jobId, process, commandString, "Running"));
            System.out.println("[" + jobId + "] " + process.pid());
        } else {
            process.waitFor();
        }
    }

    private static String shellSingleQuote(String s) {
        if (s == null)
            return "''";
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    private static void copyStream(InputStream in, OutputStream out, boolean closeOut) {
        byte[] buffer = new byte[8192];
        try {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            if (closeOut) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void runBuiltin(List<String> argv, InputStream stdin, OutputStream stdout, OutputStream stderr) {
        PrintStream out = stdout instanceof PrintStream ? (PrintStream) stdout : new PrintStream(stdout, true);
        PrintStream err = stderr instanceof PrintStream ? (PrintStream) stderr : new PrintStream(stderr, true);
        String commandName = argv.get(0);
        if (commandName.equals("echo")) {
            if (argv.size() > 1) {
                out.println(String.join(" ", argv.subList(1, argv.size())));
            } else {
                out.println();
            }
        } else if (commandName.equals("pwd")) {
            out.println(System.getProperty("user.dir"));
        } else if (commandName.equals("cd")) {
            String target = argv.size() > 1 ? argv.get(1) : "";
            File targetDir;
            if (target.equals("~")) {
                String home = System.getenv("HOME");
                targetDir = home != null ? new File(home) : new File("~");
            } else if (target.startsWith("/")) {
                targetDir = new File(target);
            } else {
                targetDir = new File(System.getProperty("user.dir"), target);
            }
            try {
                File canonical = targetDir.getCanonicalFile();
                if (canonical.isDirectory()) {
                    System.setProperty("user.dir", canonical.getAbsolutePath());
                } else {
                    err.println("cd: " + target + ": No such file or directory");
                }
            } catch (Exception e) {
                err.println("cd: " + target + ": No such file or directory");
            }
        } else if (commandName.equals("type")) {
            String target = argv.size() > 1 ? argv.get(1) : "";
            if (isBuiltin(target)) {
                out.println(target + " is a shell builtin");
            } else {
                String pathResult = findExecutableInPath(target);
                if (pathResult != null) {
                    out.println(target + " is " + pathResult);
                } else {
                    out.println(target + ": not found");
                }
            }
        } else if (commandName.equals("jobs")) {
            printJobsBuiltin(stdout, stderr);
        }
    }

    private static void printJobsBuiltin() {
        printJobsBuiltin(System.out, System.err);
    }

    private static void printJobsBuiltin(OutputStream stdout, OutputStream stderr) {
        PrintStream out = stdout instanceof PrintStream ? (PrintStream) stdout : new PrintStream(stdout, true);
        List<JobDisplay> displays = buildJobDisplays();
        for (JobDisplay display : displays) {
            out.println(display.line);
        }
        jobs.removeIf(Main::isJobDone);
    }

    private static void runPipeline(List<String> leftTokens, List<String> rightTokens) throws Exception {
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return;
        }
        String leftCommandName = leftTokens.get(0);
        String rightCommandName = rightTokens.get(0);
        boolean leftBuiltin = isBuiltin(leftCommandName);
        boolean rightBuiltin = isBuiltin(rightCommandName);

        String leftPath = leftBuiltin ? null : findExecutableInPath(leftCommandName);
        String rightPath = rightBuiltin ? null : findExecutableInPath(rightCommandName);
        if (!leftBuiltin && leftPath == null) {
            System.out.println(leftCommandName + ": command not found");
            return;
        }
        if (!rightBuiltin && rightPath == null) {
            System.out.println(rightCommandName + ": command not found");
            return;
        }

        if (!leftBuiltin && !rightBuiltin) {
            List<String> leftCommand = new ArrayList<>();
            leftCommand.add(leftPath);
            leftCommand.addAll(leftTokens.subList(1, leftTokens.size()));
            List<String> rightCommand = new ArrayList<>();
            rightCommand.add(rightPath);
            rightCommand.addAll(rightTokens.subList(1, rightTokens.size()));

            ProcessBuilder leftPb = new ProcessBuilder(leftCommand);
            ProcessBuilder rightPb = new ProcessBuilder(rightCommand);
            leftPb.directory(new File(System.getProperty("user.dir")));
            rightPb.directory(new File(System.getProperty("user.dir")));
            leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);
            rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);
            rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            Process leftProcess = leftPb.start();
            Process rightProcess = rightPb.start();

            Thread pipeThread = new Thread(
                    () -> copyStream(leftProcess.getInputStream(), rightProcess.getOutputStream(), true));
            pipeThread.start();

            rightProcess.waitFor();
            pipeThread.join();
            if (leftProcess.isAlive()) {
                leftProcess.destroy();
                leftProcess.waitFor();
            }
            return;
        }

        PipedOutputStream pipeOut = new PipedOutputStream();
        PipedInputStream pipeIn = new PipedInputStream(pipeOut, 65536);

        Thread leftThread = null;
        Process leftProcess = null;

        if (leftBuiltin) {
            leftThread = new Thread(() -> {
                try (OutputStream out = pipeOut) {
                    runBuiltin(leftTokens, new java.io.ByteArrayInputStream(new byte[0]), out, System.err);
                } catch (Exception ignored) {
                }
            });
            leftThread.start();
        } else {
            List<String> leftCommand = new ArrayList<>();
            leftCommand.add(leftPath);
            leftCommand.addAll(leftTokens.subList(1, leftTokens.size()));
            ProcessBuilder leftPb = new ProcessBuilder(leftCommand);
            leftPb.directory(new File(System.getProperty("user.dir")));
            leftPb.redirectError(ProcessBuilder.Redirect.INHERIT);
            Process leftProcessLocal = leftPb.start();
            leftProcess = leftProcessLocal;
            leftThread = new Thread(() -> copyStream(leftProcessLocal.getInputStream(), pipeOut, true));
            leftThread.start();
        }

        if (rightBuiltin) {
            runBuiltin(rightTokens, pipeIn, System.out, System.err);
            try {
                pipeIn.close();
            } catch (IOException ignored) {
            }
            if (leftThread != null) {
                leftThread.join();
            }
            if (leftProcess != null) {
                leftProcess.waitFor();
            }
            return;
        }

        List<String> rightCommand = new ArrayList<>();
        rightCommand.add(rightPath);
        rightCommand.addAll(rightTokens.subList(1, rightTokens.size()));
        ProcessBuilder rightPb = new ProcessBuilder(rightCommand);
        rightPb.directory(new File(System.getProperty("user.dir")));
        rightPb.redirectError(ProcessBuilder.Redirect.INHERIT);
        rightPb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process rightProcess = rightPb.start();

        Thread rightStdinThread = new Thread(() -> copyStream(pipeIn, rightProcess.getOutputStream(), true));
        rightStdinThread.start();

        rightProcess.waitFor();
        rightStdinThread.join();
        if (leftThread != null) {
            leftThread.join();
        }
        if (leftProcess != null && leftProcess.isAlive()) {
            leftProcess.destroy();
            leftProcess.waitFor();
        }
    }

    private static void runMultiPipeline(List<List<String>> segments) throws Exception {
        int n = segments.size();
        boolean[] isBuiltin = new boolean[n];
        String[] path = new String[n];
        for (int i = 0; i < n; i++) {
            String cmd = segments.get(i).get(0);
            isBuiltin[i] = isBuiltin(cmd);
            if (!isBuiltin[i]) {
                path[i] = findExecutableInPath(cmd);
                if (path[i] == null) {
                    System.out.println(cmd + ": command not found");
                    return;
                }
            }
        }

        // create piped streams between stages
        InputStream[] ins = new InputStream[n];
        OutputStream[] outs = new OutputStream[n];
        ins[0] = System.in;
        outs[n - 1] = System.out;
        List<Thread> threads = new ArrayList<>();
        Process[] procs = new Process[n];

        for (int i = 0; i < n - 1; i++) {
            PipedOutputStream pOut = new PipedOutputStream();
            PipedInputStream pIn = new PipedInputStream(pOut, 65536);
            outs[i] = pOut;
            ins[i + 1] = pIn;
        }

        // start external processes
        for (int i = 0; i < n; i++) {
            if (!isBuiltin[i]) {
                List<String> cmd = new ArrayList<>();
                cmd.add(path[i]);
                cmd.addAll(segments.get(i).subList(1, segments.get(i).size()));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(new File(System.getProperty("user.dir")));
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                // if stage reads from terminal, inherit; otherwise we'll pipe
                if (ins[i] == System.in) {
                    pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                }
                // if stage writes to terminal, inherit output
                if (outs[i] == System.out) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                }
                procs[i] = pb.start();
            }
        }

        // run stages
        for (int i = 0; i < n; i++) {
            final int idx = i;
            InputStream in = ins[idx];
            OutputStream out = outs[idx];
            if (isBuiltin[idx]) {
                Thread t = new Thread(() -> {
                    try {
                        runBuiltin(segments.get(idx), in, out, System.err);
                        if (out != null && out != System.out && out != System.err)
                            try {
                                out.close();
                            } catch (IOException ignored) {
                            }
                    } catch (Exception ignored) {
                    }
                });
                t.start();
                threads.add(t);
            } else {
                Process p = procs[idx];
                // feed stdin -> process, but avoid copying System.in
                if (in != null && in != System.in) {
                    Thread tIn = new Thread(() -> copyStream(in, p.getOutputStream(), true));
                    tIn.start();
                    threads.add(tIn);
                }
                // feed process stdout -> out, but skip if output inherited to terminal
                if (out != null && out != System.out) {
                    boolean closeOut = !(out == System.err);
                    Thread tOut = new Thread(() -> copyStream(p.getInputStream(), out, closeOut));
                    tOut.start();
                    threads.add(tOut);
                }
            }
        }

        // wait for last stage to finish
        if (!isBuiltin[n - 1]) {
            procs[n - 1].waitFor();
        } else {
            // join last builtin thread
            // find last thread started for builtin
            for (Thread t : threads) {
                t.join();
            }
        }

        // cleanup: destroy any remaining external processes
        for (int i = 0; i < n - 1; i++) {
            if (procs[i] != null && procs[i].isAlive()) {
                procs[i].destroy();
                procs[i].waitFor();
            }
        }
        // ensure all copy/join threads finished
        for (Thread t : threads) {
            if (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static void reapCompletedJobsBeforePrompt() {
        List<JobDisplay> displays = buildJobDisplays();
        boolean printedAnything = false;
        for (JobDisplay display : displays) {
            if (display.done) {
                System.out.println(display.line);
                printedAnything = true;
            }
        }
        if (printedAnything) {
            jobs.removeIf(Main::isJobDone);
        }
    }

    private static List<JobDisplay> buildJobDisplays() {
        List<JobDisplay> displays = new ArrayList<>();
        for (int index = 0; index < jobs.size(); index++) {
            Job job = jobs.get(index);
            String marker;
            if (index == jobs.size() - 1) {
                marker = "+";
            } else if (index == jobs.size() - 2) {
                marker = "-";
            } else {
                marker = " ";
            }
            boolean done = isJobDone(job);
            String status = done ? "Done" : job.status;
            String statusField = String.format("%-24s", status);
            String commandDisplay = job.command + (done ? "" : " &");
            String line = "[" + job.jobId + "]" + marker + "  " + statusField + commandDisplay;
            displays.add(new JobDisplay(line, done));
        }
        return displays;
    }

    private static class JobDisplay {
        final String line;
        final boolean done;

        JobDisplay(String line, boolean done) {
            this.line = line;
            this.done = done;
        }
    }

    private static boolean isBuiltin(String command) {
        return "echo".equals(command) || "exit".equals(command) || "type".equals(command) || "pwd".equals(command)
                || "cd".equals(command) || "jobs".equals(command);
    }

    private static List<String> parseCommand(String input) {
        List<String> argv = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                } else {
                    current.append(c);
                }
            } else if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                } else if (c == '\\') {
                    if (i + 1 < input.length()) {
                        char next = input.charAt(i + 1);
                        if (next == '"' || next == '\\') {
                            current.append(next);
                            i++;
                        } else {
                            current.append(c);
                        }
                    } else {
                        current.append(c);
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\\') {
                    if (i + 1 < input.length()) {
                        i++;
                        current.append(input.charAt(i));
                    }
                } else if (c == '\'') {
                    inSingleQuote = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        argv.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }

        if (current.length() > 0) {
            argv.add(current.toString());
        }

        return argv;
    }

    private static String findExecutableInPath(String command) {
        if (command.isEmpty()) {
            return null;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }

        String[] paths = pathEnv.split(File.pathSeparator);
        for (String pathDir : paths) {
            if (pathDir == null || pathDir.isEmpty()) {
                continue;
            }
            File dir = new File(pathDir);
            if (!dir.isDirectory()) {
                continue;
            }
            File candidate = new File(dir, command);
            if (candidate.exists() && candidate.canExecute()) {
                return candidate.getPath();
            }
        }
        return null;
    }

    private static class Job {
        final int jobId;
        final Process process;
        final String command;
        final String status;

        Job(int jobId, Process process, String command, String status) {
            this.jobId = jobId;
            this.process = process;
            this.command = command;
            this.status = status;
        }
    }

    private static boolean isJobDone(Job job) {
        if (!job.process.isAlive()) {
            return true;
        }
        try {
            return job.process.waitFor(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !job.process.isAlive();
        }
    }

    private static int nextJobId() {
        boolean[] used = new boolean[jobs.size() + 2];
        for (Job job : jobs) {
            if (job.jobId > 0 && job.jobId < used.length) {
                used[job.jobId] = true;
            }
        }
        for (int i = 1; i < used.length; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return jobs.size() + 1;
    }
}