package com.tianmu.agent.faq;

import java.util.List;
import java.util.function.UnaryOperator;

/** Mutations serialize on the owner/question aggregate, including session deduplication and activation. */
public interface QaStore {
    QaState get(String owner,String question);
    List<QaState.Item> list(String owner,int limit);
    QaState update(String owner,String question,QaState initial,UnaryOperator<QaState> change);
}
