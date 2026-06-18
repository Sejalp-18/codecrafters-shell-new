import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("$ ");
            String s = sc.next();
            System.out.println(s + ": command not found");
            if (s == "exit")
                return;
        }

    }
}
