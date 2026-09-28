package sg.edu.smu.cs203.market;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "sg.edu.smu.cs203")
@EnableScheduling
public class EnergyMarketServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(EnergyMarketServiceApplication.class, args);
	}

}
