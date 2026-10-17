package app.sense.releasecheck;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.*;
import java.io.File;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the signed consumer APK without loading any of its classes. */
@RunWith(AndroidJUnit4.class)
public class ReleaseInstallationTest {
    private final UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private boolean safeToCapture;

    @After
    public void capturePublicScreen() {
        // Never capture the private recovery-key dialog, even if signup fails.
        if (safeToCapture) {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            device.takeScreenshot(new File(context.getExternalFilesDir(null), "release-screen.png"));
        }
    }

    private void launch() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage("app.sense");
        assertNotNull("Signed consumer application must be installed", intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
    }

    private UiObject2 text(String value, long timeout) {
        UiObject2 result = device.wait(Until.findObject(By.text(value)), timeout);
        assertNotNull("Expected screen element: " + value, result);
        return result;
    }

    private void reveal(UiSelector selector) throws Exception {
        new UiScrollable(new UiSelector().scrollable(true)).scrollIntoView(selector);
    }

    private void click(String value) throws Exception {
        if (!device.hasObject(By.text(value))) reveal(new UiSelector().text(value));
        UiObject2 target = device.wait(Until.findObject(By.text(value).enabled(true)), 15000);
        assertNotNull("Action must be enabled: " + value, target);
        target.click();
    }

    private void enter(String id, String value) throws Exception {
        assertTrue("Account form must be ready", device.wait(Until.hasObject(By.pkg("app.sense").scrollable(true)), 30000));
        if (!device.hasObject(By.res(id))) reveal(new UiSelector().resourceId(id));
        UiObject2 field = device.wait(Until.findObject(By.res(id)), 15000);
        assertNotNull("Expected input: " + id, field);
        field.setText(value);
        // Do not press Back when no keyboard is open: that would exit SENSE.
        if (device.hasObject(By.pkg(Pattern.compile(".*inputmethod.*")))) device.pressBack();
    }

    @Test
    public void freshInstallRegistersRestartsAndRecovers() throws Exception {
        launch();
        String username = "release-" + Long.toString(System.currentTimeMillis(), 36);
        enter("sense_username", username);
        click("Create my username");
        text("Keep this private", 60000);
        UiObject2 keyNode = device.wait(Until.findObject(By.text(Pattern.compile("[A-Za-z0-9_-]{43}"))), 15000);
        assertNotNull("New account must display its private recovery key", keyNode);
        String recoveryKey = keyNode.getText(); // Memory only: never logged or saved.
        click("Done");
        text("Stay in tune.", 30000);
        safeToCapture = true;

        device.executeShellCommand("am force-stop app.sense");
        launch();
        text("Stay in tune.", 30000);
        click("You");
        text("Make space for you.", 15000);
        reveal(new UiSelector().text("@" + username));
        text("@" + username, 15000);
        click("Sign out");
        enter("sense_username", username);
        enter("sense_recovery_key", recoveryKey);
        click("Sign in");
        text("Make space for you.", 60000);
        reveal(new UiSelector().text("@" + username));
        text("@" + username, 15000);
        click("Home");
        text("Stay in tune.", 15000);
    }
}
