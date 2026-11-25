package cn.dental.identity.application;
import java.util.Arrays;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
/** Explicit non-web invocation only. Passwords must never be passed in command line arguments. */
@Component
@ConditionalOnProperty(name="app.bootstrap.enabled",havingValue="true")
public class BootstrapCommand implements ApplicationRunner {
    private final BootstrapService bootstrap;private final ConfigurableApplicationContext context;
    public BootstrapCommand(BootstrapService bootstrap,ConfigurableApplicationContext context){this.bootstrap=bootstrap;this.context=context;}
    @Override public void run(ApplicationArguments args){
        if(!"none".equals(context.getEnvironment().getProperty("spring.main.web-application-type")))throw new IllegalStateException("Bootstrap requires non-web mode");
        var console=System.console();if(console==null)throw new IllegalStateException("Bootstrap requires an interactive terminal");
        String name=console.readLine("Administrator staff name: ");String login=console.readLine("Login name (3-100 lowercase ASCII characters): ");String basis=console.readLine("Owner approval reference (no secrets): ");
        char[] first=console.readPassword("Initial password (12+ characters, max 72 UTF-8 bytes): ");char[] second=console.readPassword("Confirm password: ");
        try {
            if(first==null||second==null||!Arrays.equals(first,second))throw new IllegalArgumentException("Passwords do not match");
            long id=bootstrap.initialize(name,login,new String(first),basis);
            console.printf("Administrator account %d created; password change required. No clinical role granted.%n",id);
        } finally {if(first!=null)Arrays.fill(first,'\0');if(second!=null)Arrays.fill(second,'\0');context.close();}
    }
}
