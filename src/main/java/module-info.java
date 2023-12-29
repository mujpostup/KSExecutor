module com.jetbrains.internship.ksexecutor {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.controlsfx.controls;
    requires org.kordamp.bootstrapfx.core;
    requires org.fxmisc.richtext;
    requires reactfx;

    opens com.jetbrains.internship.ksexecutor to javafx.fxml;
    exports com.jetbrains.internship.ksexecutor;
}