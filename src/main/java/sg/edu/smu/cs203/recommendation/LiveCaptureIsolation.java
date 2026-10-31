package sg.edu.smu.cs203.recommendation;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolate expected input failures and roll back failed JDBC writes to a savepoint. */
@Component
public class LiveCaptureIsolation {
    private final TransactionTemplate read,write;
    public LiveCaptureIsolation(PlatformTransactionManager transactions) {
        read=new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        write=new TransactionTemplate(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }
    // The reader's REQUIRED advice may mark its transaction rollback-only on failure.
    // Give reads their own transaction so that flag cannot poison the issue transaction.
    <T> T read(Supplier<T> work) { return read.execute(status -> work.get()); }
    <T> T write(Supplier<T> work) { return write.execute(status -> work.get()); }
}
