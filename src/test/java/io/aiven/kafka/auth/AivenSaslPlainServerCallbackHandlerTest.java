import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.aiven.kafka.auth.AivenSaslPlainServerCallbackHandler;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import org.apache.kafka.common.security.plain.PlainLoginModule;
import org.junit.Test;

public class AivenSaslPlainServerCallbackHandlerTest {
  static final String USERS_JSON = "[{\"username\":\"testuser\",\"password\":\"testpassword\"}]";

  @Test
  public void testAivenSaslPlainServerCallbackHandler() throws IOException {
    Path tempPath = Files.createTempDirectory("test-aiven-kafka-sasl-plain-handler");
    Path configFilePath = Paths.get(tempPath.toString(), "sasl_passwd.json");

    File passwdJson = new File(configFilePath.toString());

    Files.write(configFilePath, USERS_JSON.getBytes());

    Map<String, String> entryConfigs = new HashMap<String, String>();
    entryConfigs.put("users.config", configFilePath.toString());
    AppConfigurationEntry entry = new AppConfigurationEntry(PlainLoginModule.class.getName(),
            AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, entryConfigs);
    List<AppConfigurationEntry> jaasConfigs = new ArrayList<AppConfigurationEntry>();
    jaasConfigs.add(entry);

    AivenSaslPlainServerCallbackHandler handler = new AivenSaslPlainServerCallbackHandler();
    handler.configure(null, "PLAIN", jaasConfigs);

    assertTrue(handler.authenticate("testuser", "testpassword".toCharArray()));
    assertFalse(handler.authenticate("testuser", "invalidpassword".toCharArray()));
    assertFalse(handler.authenticate("invaliduser", "testpassword".toCharArray()));
  }
}
