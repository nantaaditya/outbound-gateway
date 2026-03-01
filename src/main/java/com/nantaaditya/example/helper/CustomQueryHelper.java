package com.nantaaditya.example.helper;

import com.nantaaditya.example.entity.DeadLetterProcess;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class CustomQueryHelper {

  private final DatabaseClient databaseClient;
  private final TransactionalOperator transactionalOperator;

  private static final String FIND_UNPROCESSED_DEAD_LETTER_PROCESS = """
      select * from dead_letter_process
      where process_type = :processType and process_name = :processName and processed is false
      order by created_date asc
      limit :limit
      for update skip locked""";

  public Flux<DeadLetterProcess> findUnprocessedDeadLetterProcesses(String processType, String processName, int size) {
    return databaseClient.sql(FIND_UNPROCESSED_DEAD_LETTER_PROCESS)
        .bind("processType", processType)
        .bind("processName", processName)
        .bind("limit", size)
        .map((row, metadata) -> DeadLetterProcess.from(row))
        .all()
        .as(transactionalOperator::transactional);
  }
}
