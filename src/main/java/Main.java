import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.print("$ ");
        Scanner sc = new Scanner(System.in);
        String s = sc.next();
        System.out.print(s + ": command not found");
    }
}
