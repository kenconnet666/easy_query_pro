package example.boot4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Boot 4 keeps the upstream starter's auto-configuration; the application only imports the Pro installation order.
 */
@SpringBootApplication
@Import(ProConfiguration.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
