package com.jetbrains.internship.ksexecutor;

import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;

import java.io.*;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class KSExecutorApplication extends Application {

    private static final String[] KEYWORDS = new String[] {
            "as", "as?", "break", "class", "continue",
            "do", "else", "false", "for", "fun",
            "if", "in", "!in", "interface", "is",
            "!is", "null", "object", "package", "return",
            "super", "this", "throw", "true", "try",
            "typealias", "typeof", "val", "var", "when",
            "while"
    };

    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
    private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
    private static final String COMMENT_PATTERN = "//[^\n]*" + "|" + "/\\*(.|\\R)*?\\*/";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<KEYWORD>" + KEYWORD_PATTERN + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
    );

    private static final FXMLLoader fxmlLoader = new FXMLLoader(KSExecutorApplication.class.getResource("hello-view.fxml"));
    private CodeArea codeArea;
    private ExecutorService executorService;

    @Override
    public void start(Stage stage) throws IOException {
        Screen screen = Screen.getPrimary();
        Scene scene = new Scene(fxmlLoader.load(), screen.getBounds().getWidth() * 3 / 4, screen.getBounds().getHeight() * 3 / 4);
        scene.getStylesheets().add(getClass().getResource("style.css").toExternalForm());
        stage.setTitle("KS Executor");
        stage.setScene(scene);
        stage.getIcons().add(new Image(getClass().getResource("images/icon.png").toExternalForm()));

        codeArea = (CodeArea)fxmlLoader.getNamespace().get("codeArea");
        executorService = Executors.newSingleThreadExecutor();
        Subscription cleanupWhenDone = codeArea.multiPlainChanges()
                .successionEnds(Duration.ofMillis(200))
                .retainLatestUntilLater(executorService)
                .supplyTask(this::computeHighlightingAsync)
                .awaitLatest(codeArea.multiPlainChanges())
                .filterMap(t -> {
                    if(t.isSuccess()){
                        return Optional.of(t.get());
                    }else{
                        t.getFailure().printStackTrace();
                        return Optional.empty();
                    }
                })
                .subscribe(this::applyHighlighting);

        stage.setOnCloseRequest(windowEvent -> {
            Process process = KSExecutorController.getProcess();
            if(process != null && process.isAlive()){
                KSExecutorController.getProcess().destroyForcibly();
                try {
                    process.getInputStream().close();
                    process.getOutputStream().flush();
                    process.getErrorStream().close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            if(KSExecutorController.getIsScriptRunning()){
                windowEvent.consume();
            }
            cleanupWhenDone.unsubscribe();
        });

        stage.show();
    }

    public static void main(String[] args) {
        launch();
    }

    @Override
    public void stop() {
        executorService.shutdown();
    }

    private Task<StyleSpans<Collection<String>>> computeHighlightingAsync() {
        String text = codeArea.getText();
        Task<StyleSpans<Collection<String>>> task = new Task<>() {
            @Override
            protected StyleSpans<Collection<String>> call(){
                return computeHighlighting(text);
            }
        };
        executorService.execute(task);
        return task;
    }

    private void applyHighlighting(StyleSpans<Collection<String>> highlighting) {
        codeArea.setStyleSpans(0, highlighting);
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastKwEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
        while(matcher.find()) {
            String styleClass =
                    matcher.group("KEYWORD") != null ? "keyword" :
                    matcher.group("STRING") != null ? "string" :
                    matcher.group("COMMENT") != null ? "comment" :
                    null;
            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKwEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKwEnd = matcher.end();
        }
        spansBuilder.add(Collections.emptyList(), text.length() - lastKwEnd);
        return spansBuilder.create();
    }
}