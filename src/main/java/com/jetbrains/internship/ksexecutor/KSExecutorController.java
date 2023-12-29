package com.jetbrains.internship.ksexecutor;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.fxmisc.richtext.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KSExecutorController {
    private static Process process;
    public static Process getProcess(){
        return process;
    }
    private static boolean isScriptRunning = false;
    public static boolean getIsScriptRunning(){
        return isScriptRunning;
    }
    private static final ProcessBuilder pb;
    private static final File scriptFile;
    private static final Pattern locationDescription;
    private static final Map<String, Integer> parentheses = new HashMap<>();
    private static final String[] parenthesesSymbols = {"(", ")", "{", "}"};

    static{
        scriptFile = new File("script.kts");

        try {
            scriptFile.createNewFile();
            scriptFile.deleteOnExit();
        } catch (IOException e) {
            e.printStackTrace();
        }

        String command = "kotlinc -script " + scriptFile.getName();
        if(System.getProperty("os.name").toLowerCase().startsWith("windows")){
            pb = new ProcessBuilder("cmd", "/c", command);
        }else{
            pb = new ProcessBuilder("/usr/bin/env", command);
        }
        pb.directory(new File(System.getProperty("user.dir")));

        locationDescription = Pattern.compile(scriptFile.getName() + ":(([0-9]+):([0-9]+))");
    }
    @FXML
    public Button runButton;
    @FXML
    private HBox hbox;
    @FXML
    private CodeArea codeArea;
    @FXML
    private StyleClassedTextArea styledTextArea;

    public void initialize(){
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.setOnKeyPressed(keyEvent -> {
            KeyCode keyCode = keyEvent.getCode();
            switch(keyCode){
                case ENTER -> {
                    int currentParagraph = codeArea.getCurrentParagraph();
                    String str = codeArea.getText(currentParagraph - 1);
                    String trimmedStr = str.trim();
                    String indention = str.substring(0, trimmedStr.isEmpty() ? str.length() : str.indexOf(trimmedStr));
                    codeArea.insertText(currentParagraph, 0, indention);
                    if(trimmedStr.endsWith("{") || trimmedStr.endsWith("(")){
                        String endSymbol = String.valueOf(trimmedStr.charAt(trimmedStr.length() - 1));
                        String closingBracket = "(".equals(endSymbol) ? ")" : "}";
                        codeArea.insertText(currentParagraph, codeArea.getParagraphLength(currentParagraph), "\t");
                        if(!parentheses.get(endSymbol).equals(parentheses.get(closingBracket))){
                            codeArea.insertText(currentParagraph, codeArea.getParagraphLength(currentParagraph), "\n" + indention + (trimmedStr.endsWith("{") ? "}" : ")"));
                            Integer value = parentheses.get(closingBracket);
                            parentheses.put(closingBracket, value == null ? 1 : value + 1);
                        }
                        codeArea.moveTo(currentParagraph, codeArea.getParagraphLength(currentParagraph));
                    }
                }
                case OPEN_BRACKET, CLOSE_BRACKET, DIGIT0, DIGIT9 -> {
                    if(keyEvent.isShiftDown()) {
                        String c = keyCode.getChar();
                        c = "[".equals(c) ? "{" : "]".equals(c) ? "}" : "9".equals(c) ? "(" : ")";
                        Integer value = parentheses.get(c);
                        parentheses.put(c , value == null ? 1 : value + 1);
                    }
                }
                case BACK_SPACE, DELETE -> {
                    String code = codeArea.getText();
                    for(String parenthesis : parenthesesSymbols){
                        parentheses.put(parenthesis, code.length() - (code = code.replace(parenthesis, "")).length());
                    }
                }
                default -> {}
            }
        });
        styledTextArea.setOnMouseClicked(mouseEvent -> {
            if(mouseEvent.getEventType().equals(MouseEvent.MOUSE_CLICKED)){
                if(styledTextArea.getStyleAtPosition(styledTextArea.getCaretPosition()).contains("link")){
                    int clickedParagraphIndex = styledTextArea.getCurrentParagraph();
                    Matcher matcher = locationDescription.matcher(styledTextArea.getText(clickedParagraphIndex));
                    if(matcher.find()){
                        int index = matcher.group().length() - matcher.group(1).length();
                        if(styledTextArea.getCaretColumn() >= index && styledTextArea.getCaretColumn() <= index + matcher.group(1).length()){
                            codeArea.requestFocus();
                            codeArea.moveTo(Integer.parseInt(matcher.group(2)) - 1, Integer.parseInt(matcher.group(3)) - 1);
                        }
                    }
                }
            }
        });
        hbox.getStyleClass().add("hbox");
        runButton.getStyleClass().add("start-button");
        Region icon = new Region();
        icon.getStyleClass().add("icon");
        runButton.setGraphic(icon);

        ProgressIndicator progressIndicator = new ProgressIndicator();

        runButton.setOnMouseClicked(mouseEvent -> {
            ObservableList<String> styles = runButton.getStyleClass();
            if("start-button".equals(styles.removeLast())){
                isScriptRunning = true;
                new Thread(() ->{
                    Platform.runLater(() -> {
                        styles.add("indicator-button");
                        runButton.setGraphic(progressIndicator);
                        styledTextArea.clear();
                    });
                    final String code = new String(codeArea.getText().getBytes(StandardCharsets.UTF_8));
                    try {
                        try(BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(scriptFile, StandardCharsets.UTF_8))) {
                            bufferedWriter.write(code);
                        }
                        long start = System.currentTimeMillis();
                        process = pb.start();

                        printOutputStream();
                        printErrorStream();

                        Platform.runLater(() -> {
                           styledTextArea.append("\nExecution time: " + (System.currentTimeMillis() - start) / 1000.0 + "s.", "yellow");
                           styledTextArea.requestFollowCaret();
                        });
                        process.getOutputStream().flush();
                    } catch (InterruptedException | IOException e) {
                        Platform.runLater(() -> styledTextArea.append(e.getMessage(), "red"));
                    }finally{
                        isScriptRunning = false;
                        Platform.runLater(() -> {
                            styles.removeLast();
                            styles.add("start-button");
                            runButton.setGraphic(icon);
                        });
                    }
                }).start();
            }else{
                styles.add("indicator-button");
            }
        });
    }

    public void printOutputStream() throws IOException, InterruptedException{
        try(BufferedReader br = process.inputReader(StandardCharsets.UTF_8)){
            String buffer;
            while((buffer = br.readLine()) != null){
                String buff = buffer + '\n';
                Platform.runLater(() -> {
                    styledTextArea.append(buff, "white");
                    styledTextArea.requestFollowCaret();
                });
                Thread.sleep(1L);
            }
        }

    }
    public void printErrorStream() throws IOException, InterruptedException{
        try(BufferedReader br = process.errorReader(StandardCharsets.UTF_8)){
            String buffer;
            while ((buffer = br.readLine()) != null) {
                final String buff = buffer + "\n";
                final Matcher matcher = locationDescription.matcher(buff);
                if(matcher.find()){
                    Platform.runLater(() ->{
                        styledTextArea.append(scriptFile.getName() + ":", "red");
                        styledTextArea.append(matcher.group(1), "link");
                        styledTextArea.append(buff.replace(matcher.group(), ""), "red");
                    });
                    continue;
                }
                Platform.runLater(() -> {
                    styledTextArea.append(buff, "red");
                    styledTextArea.requestFollowCaret();
                });
                Thread.sleep(1L);
            }
        }
    }
}