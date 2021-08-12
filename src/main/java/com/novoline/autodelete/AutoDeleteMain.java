package com.novoline.autodelete;

public class AutoDeleteMain {

    public static void main(String[] args) {
        String token = System.getenv("TOKEN");
        new AutoDeleteBot(token);
    }
}
