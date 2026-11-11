import java.io.*;   //io = インプット・アウトプット（多分）
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Judge {

    // 実行時間制限（秒）
    private static final long TIME_LIMIT_SECONDS = 5;

    // stdout 出力同期用．並行処理中，これを引数に持つスレッドに主導権が渡る（クイズの回答ボタンみたいな？）
    private static final Object OUTPUT_LOCK = new Object();

    //無限ループ停止用
    static final int MAX_LINES = 50;
    static final int[] lineCount = {0}; 
    static final int MAX_CHARS = 500;
    static final int[] charCount = {0};

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Judge Main < input.txt");
            return;
        }

        String className = args[0]; //中身は学生のクラス名

        // =========================
        // 基準ディレクトリ(呼び出し元シェルスクリプトの場所)を取得
        // =========================
        // 未設定の場合はこれまで通りカレントディレクトリを基準にする。
        String scriptDirEnv = System.getenv("JUDGE_SCRIPT_DIR");
        Path scriptDir = (scriptDirEnv != null)
                ? Paths.get(scriptDirEnv)
                : Paths.get(".");

        // =========================
        // 学生プログラムを探索
        // =========================
        // Path baseDir = scriptDir.resolve("").normalize();
        // Path classFile = null;
        // Path classDir = null;

        // try (DirectoryStream<Path> stream = Files.newDirectoryStream(scriptDir)) {
        //     for (Path dir : stream) {
        //         Path candidate = dir.resolve(className + ".class");

        //         if (Files.exists(candidate)) {
        //             classFile = candidate;
        //             classDir = dir;
        //             break;
        //         }
        //     }
        // }catch (IOException e) {
        //     e.printStackTrace();
        //     return;
        // }

        Path classDir = scriptDir.normalize();
        Path classFile = classDir.resolve(className + ".class");

        if (!Files.exists(classFile)) {
            System.out.println(className + ".class が見つかりません: " + classFile);
            return;
        }


        // 出力先を作成（シェルスクリプトの場所から見て 管理用/output に固定）
        Path outputDir = scriptDir.resolve("管理用/output").normalize();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        Path outputFile = outputDir.resolve(className + ".txt");

        //.txtに書き込む
        try {
            PrintStream tee = new PrintStream(
                    new TeeOutputStream(
                            System.out, new FileOutputStream(outputFile.toFile())),    //標準出力とtxtファイルに同時に書き込む
                    true,
                    StandardCharsets.UTF_8);

            System.setOut(tee);

            PrintStream teeErr = new PrintStream(
                    new TeeOutputStream(
                            System.err, new FileOutputStream(outputFile.toFile(), true)),
                    true,
                    StandardCharsets.UTF_8);

            System.setErr(teeErr);

        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // =========================
        // 入力読み込み
        // =========================
        List<List<String>> testCases = new ArrayList<>();

        Scanner scanner = new Scanner(
            new InputStreamReader(System.in, StandardCharsets.UTF_8));  //System.in = ターミナルから取得（ここでは < があるのでtxtファイルから取得）

        while (scanner.hasNextLine()) {

            String line = scanner.nextLine().trim();

            if (line.isEmpty()) continue;

            testCases.add(Arrays.asList(line.split("\\s")));    //\sは空白文字（半角スペース、タブ文字他）を表す制御文字
        }

        for (int i = 0; i < testCases.size(); i++) {

            System.out.println();
            System.out.println("===== テスト" + (i + 1) + " =====");

            runProgram(className, classDir, testCases.get(i));

        }

        // =========================
        // メッセージ(差分機能が使いにくくなるのでローカルテストでは使わないかも)
        // =========================

            
    }

    // =========================
    // java-history-monitor のラッパーを迂回させたPATHを作る
    // =========================
    // Judgeが内部で学生プログラムを起動する際は本物のjavaを直接使う必要がある。理由は2つ：
    //   ① ラッパー経由だと余計なログが二重に記録される
    //   ② ラッパーはFIFO経由で本物のjavaを孫プロセスとして起動するため、Process#destroyForcibly()
    //   が孫プロセス(学生プログラム本体)まで届かず、TLE/無限ループ対策の強制終了が効かなくなる
    private static Map<String, String> buildEnvWithoutMonitorPath(Map<String, String> baseEnv) {
        String path = baseEnv.get("PATH");
        if (path == null) {
            return baseEnv;
        }
        String monitorBin = System.getProperty("user.home") + "/.java-monitor/bin";
        String newPath = Arrays.stream(path.split(":"))
                .filter(p -> !p.equals(monitorBin))
                .collect(Collectors.joining(":"));
        baseEnv.put("PATH", newPath);
        return baseEnv;
    }

    // =========================
    // 学生プログラムを起動
    // =========================
    private static void runProgram(String className, Path classDir, List<String> tokens) {
         try {

        System.out.println("[実行結果]");
        System.out.println("% java " + className);
                

        // Path classDir = classFile.getParent();  //クラスファイルが存在するディレクトリ

        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-cp",
            classDir.toString(),
            className
        );

        // java-history-monitor のラッパーを迂回し、本物のjavaを直接使う
        buildEnvWithoutMonitorPath(pb.environment());

        Process process = pb.start();

        // =========================
        // stdout 監視 Thread
        // =========================
        Thread stdoutThread = new Thread(() -> {
            try (
                    InputStreamReader reader =
                            new InputStreamReader(
                                    process.getInputStream(),   //java クラス名 を実行した時の標準出力を読み取るための，inputStreamをくださいって意味
                                    StandardCharsets.UTF_8)
            ) {

                int ch;

                while ((ch = reader.read()) != -1) {

                    synchronized (OUTPUT_LOCK) {
                        System.out.print((char) ch);    //学生のjavaファイルの標準出力を一文字ずつ書き出す
                        System.out.flush();     //バッファに残っているデータを流す（消す）

                        //無限ループ対策処理
                        if (ch == '\n') {   //\nは改行のエスケープシーケンス（制御文字）
                            lineCount[0]++;

                            if (lineCount[0] >= MAX_LINES) {
                                process.destroyForcibly();
                                break;
                            }
                            
                        }  
                        charCount[0]++;      //1行の無限ループ対策処理
                        if (charCount[0] > MAX_CHARS) {
                            process.destroyForcibly();
                        }
                    }
                }

            } catch (IOException ignored) {
            }
        });

        stdoutThread.setDaemon(true);

        // =========================
        // stderr 監視 Thread
        // =========================
        Thread stderrThread = new Thread(() -> {
            try (
                    InputStreamReader reader =
                            new InputStreamReader(
                                    process.getErrorStream(),   //java クラス名 を実行した時のエラーメッセージを取ってる
                                    StandardCharsets.UTF_8)
            ) {

                int ch;

                while ((ch = reader.read()) != -1) {

                    synchronized (OUTPUT_LOCK) {
                        System.out.print((char) ch);
                        System.out.flush();
                    }
                }

            } catch (IOException ignored) {
            }
        });

        stderrThread.setDaemon(true);

        stdoutThread.start();
        stderrThread.start();

        // =========================
        // 学生プログラムへ標準入力を送信
        // =========================
        PrintWriter writer =
            new PrintWriter(    //文字列で書ける．便利らしい．
                    new OutputStreamWriter(
                            process.getOutputStream(),  //java クラス名 を実行した時の標準入力(txtファイル)を取ってる
                            StandardCharsets.UTF_8),
                    true);

        for (String token : tokens) {

            // stdout の flush を待つ
            Thread.sleep(50);

            synchronized (OUTPUT_LOCK) {

                // 入力位置表示
                System.out.println("[INPUT]");
                System.out.flush();

                // 学生プログラムへ入力送信
                writer.println(token);
                writer.flush();
            }

            // 少し待機
            Thread.sleep(50);
        }
        

        // =========================
        // TLE 判定
        // =========================
        boolean finished =
                process.waitFor(
                        TIME_LIMIT_SECONDS, //数値
                        TimeUnit.SECONDS);  //単位(ここでは秒)

        if (!finished || process.isAlive()) {

            // 強制終了
            process.destroyForcibly();  //終了命令．プロセスが強制終了されない場合もあるらしい

            // OS側終了待機
            process.waitFor(1, TimeUnit.SECONDS);

            // OSに対して，それぞれのStreamを閉じたいと申請
            safeClose(process.getInputStream());
            safeClose(process.getErrorStream());
            safeClose(process.getOutputStream());

            synchronized (OUTPUT_LOCK) {
                System.out.println();
                System.out.println("Time Limit Exceeded");
                System.out.flush();
            }

            return;
        }
        // 出力取りこぼし対策
        Thread.sleep(100);

        System.out.println(" ");

        } catch (Exception e) {
            System.out.println();
            e.printStackTrace();    //エラー情報をターミナルに表示
        }

        // =========================
        // 入力内容表示
        // =========================
        System.out.println("[入力内容]");
        if (tokens.isEmpty()) {
            System.out.println("なし");
        } else {
            System.out.println(String.join(" ", tokens));
        }

    }

    // =========================
    // close 安全化
    // =========================
    private static void safeClose(Closeable closeable) {

        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ignored) {
        }
    }
}


// =========================
// output.txtとターミナル両方に表示
// =========================
class TeeOutputStream extends OutputStream {

    private final OutputStream out1;    //書くところ１
    private final OutputStream out2;    //書くところ２

    public TeeOutputStream(OutputStream out1, OutputStream out2) {
        this.out1 = out1;
        this.out2 = out2;
    }

    @Override
    public void write(int b) throws IOException {
        out1.write(b);
        out2.write(b);
    }

    @Override
    public void flush() throws IOException {
        out1.flush();
        out2.flush();
    }

    @Override
    public void close() throws IOException {
        out1.close();
        out2.close();
    }
}
