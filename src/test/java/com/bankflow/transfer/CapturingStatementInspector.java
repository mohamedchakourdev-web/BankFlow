package com.bankflow.transfer;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class CapturingStatementInspector implements StatementInspector {

    public static final List<String> SQL = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        SQL.add(sql);
        return sql;
    }
}
