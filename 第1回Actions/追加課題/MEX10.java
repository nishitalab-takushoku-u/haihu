/*
 * [問題]
 *  次の条件を満たす MEX10.java を作成しなさい。
 *  整数 f を入力して、f の正負を調べて次のメッセージを表示する。
 *      f が 0 より大きいときは「f は正の数です。」
 *      f が 0 ならば「f は 0 です。」
 *      f が 0 よりも小さいときは「f は負の数です。」
 * 
 *  【実行例 1】（ 5 はユーザによる入力）
 *      % java MEX10
 *      f = 5
 *      f は正の数です。
 * 
 *  【実行例 2】（ -7 はユーザによる入力）
 *      % java MEX10
 *      f = -7
 *      f は負の数です。
 * 
 */


import java.util.Scanner;
public class MEX10 { 
    public static void main (String [ ]args ) { 
        System.out.print("f= ");
        Scanner scanner = new Scanner(System.in);
        int f = scanner.nextInt();

        // ↑上記はSystem.console().readLine() と Integer.parseInt() の代わりの命令
        
        // ↓ここから下にプログラムを記述

    } 
 }
