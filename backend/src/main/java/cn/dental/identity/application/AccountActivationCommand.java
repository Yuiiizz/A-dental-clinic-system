package cn.dental.identity.application;
import java.util.Arrays;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 显式非Web交互命令：为待激活员工账号完成首次激活。密码绝不通过命令行参数、日志或审计传递。
 * 只激活尚无密码的待激活账号，不改原 bootstrap 的用途与一次性边界；不提供公网激活接口。
 */
@Component
@ConditionalOnProperty(name="app.activation.enabled",havingValue="true")
public class AccountActivationCommand implements ApplicationRunner {
    private final AccountActivationService activation;private final ConfigurableApplicationContext context;
    public AccountActivationCommand(AccountActivationService activation,ConfigurableApplicationContext context){this.activation=activation;this.context=context;}
    @Override public void run(ApplicationArguments args){
        if(!"none".equals(context.getEnvironment().getProperty("spring.main.web-application-type")))throw new IllegalStateException("Account activation requires non-web mode");
        var console=System.console();if(console==null)throw new IllegalStateException("Account activation requires an interactive terminal");
        String login=console.readLine("Pending employee login name: ");
        String basis=console.readLine("Approval reference (no secrets): ");
        char[] first=console.readPassword("Initial password (12+ characters, max 72 UTF-8 bytes): ");
        char[] second=console.readPassword("Confirm password: ");
        try {
            if(first==null||second==null||!Arrays.equals(first,second))throw new IllegalArgumentException("Passwords do not match");
            long id=activation.activate(login,new String(first),basis);
            console.printf("Employee account %d activated; password change required on first login. No role granted.%n",id);
        } finally {if(first!=null)Arrays.fill(first,'\0');if(second!=null)Arrays.fill(second,'\0');context.close();}
    }
}
