// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.


package com.starrocks.sql.analyzer;

import com.starrocks.qe.SessionVariable;
import com.starrocks.sql.ast.CreateViewStmt;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.SetStmt;
import com.starrocks.sql.ast.SetType;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.SystemVariable;
import com.starrocks.sql.ast.UserVariable;
import com.starrocks.sql.parser.SqlParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

public class AST2StringBuilderTest {
    @BeforeAll
    public static void beforeClass() throws Exception {
        AnalyzeTestUtil.init();
    }

    @Test
    public void testNot() {
        {
            String sql;
            sql = "CREATE VIEW v3 AS \n" +
                    "SELECT v1 FROM t0 WHERE ((NOT false) IS NULL);";
            List<StatementBase>
                    statementBase =
                    SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
            Assertions.assertEquals(1, statementBase.size());
            StatementBase baseStmt = statementBase.get(0);
            Analyzer.analyze(baseStmt, AnalyzeTestUtil.getConnectContext());
            Assertions.assertTrue(baseStmt instanceof CreateViewStmt);
            CreateViewStmt viewStmt = (CreateViewStmt) baseStmt;
            Assertions.assertEquals(viewStmt.getInlineViewDef(),
                    "SELECT `test`.`t0`.`v1`\nFROM `test`.`t0`\nWHERE (NOT FALSE) IS NULL");
        }
        {
            String sql;
            sql = "CREATE VIEW v3 AS \n" +
                    "SELECT v1 FROM t0 WHERE ((NOT false) IS NOT NULL);";
            List<StatementBase>
                    statementBase =
                    SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
            Assertions.assertEquals(1, statementBase.size());
            StatementBase baseStmt = statementBase.get(0);
            Analyzer.analyze(baseStmt, AnalyzeTestUtil.getConnectContext());
            Assertions.assertTrue(baseStmt instanceof CreateViewStmt);
            CreateViewStmt viewStmt = (CreateViewStmt) baseStmt;
            Assertions.assertEquals(viewStmt.getInlineViewDef(),
                    "SELECT `test`.`t0`.`v1`\nFROM `test`.`t0`\nWHERE (NOT FALSE) IS NOT NULL");
        }
    }

    @Test
    public void testSet() {
        // 1. one global statement
        String sql = "SET GLOBAL time_zone = 'Asia/Shanghai'";

        List<StatementBase> statementBase = SqlParser.parse(
                sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, statementBase.size());
        SetStmt originStmt = (SetStmt) statementBase.get(0);

        System.err.println(sql + " -> " + AstToStringBuilder.toString(originStmt));

        statementBase = SqlParser.parse(
                AstToStringBuilder.toString(originStmt), AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, statementBase.size());
        SetStmt convertStmt = (SetStmt) statementBase.get(0);

        Assertions.assertEquals(1, convertStmt.getSetListItems().size());
        Assertions.assertEquals(SetType.GLOBAL, ((SystemVariable) convertStmt.getSetListItems().get(0)).getType());
        Assertions.assertEquals(AstToStringBuilder.toString(originStmt), AstToStringBuilder.toString(convertStmt));

        // 2. two default statement
        sql = "SET time_zone = 'Asia/Shanghai', allow_default_partition=true;";
        statementBase = SqlParser.parse(
                sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, statementBase.size());
        originStmt = (SetStmt) statementBase.get(0);

        System.err.println(sql + " -> " + AstToStringBuilder.toString(originStmt));

        statementBase = SqlParser.parse(
                AstToStringBuilder.toString(originStmt), AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, statementBase.size());
        convertStmt = (SetStmt) statementBase.get(0);

        Assertions.assertEquals(2, convertStmt.getSetListItems().size());
        Assertions.assertEquals(SetType.SESSION, ((SystemVariable) convertStmt.getSetListItems().get(0)).getType());
        Assertions.assertEquals(SetType.SESSION, ((SystemVariable) convertStmt.getSetListItems().get(1)).getType());
        Assertions.assertEquals(AstToStringBuilder.toString(originStmt), AstToStringBuilder.toString(convertStmt));
    }

    @Test
    public void testUserVariable() {

        String sql = "SET time_zone = 'Asia/Shanghai', allow_default_partition=true, " +
                "@var1=1, " +
                "@var2 = cast('2020-01-01' as date)," +
                "@var3 = \"foo\"," +
                "@var4 = 1.23," +
                "@`select` = 1 + 2 * 3;";
        List<StatementBase> statementBase = SqlParser.parse(
                sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, statementBase.size());
        SetStmt originStmt = (SetStmt) statementBase.get(0);
        Analyzer.analyze(originStmt, AnalyzeTestUtil.getConnectContext());
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) originStmt.getSetListItems().get(2));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) originStmt.getSetListItems().get(3));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) originStmt.getSetListItems().get(4));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) originStmt.getSetListItems().get(5));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) originStmt.getSetListItems().get(6));
        Assertions.assertEquals("SET SESSION `time_zone` = 'Asia/Shanghai',SESSION `allow_default_partition` = TRUE," +
                "@`var1` = cast (1 as tinyint(4))," +
                "@`var2` = cast ('2020-01-01' as date)," +
                "@`var3` = cast ('foo' as varchar)," +
                "@`var4` = cast (1.23 as decimal(3, 2))," +
                "@`select` = cast (7 as int(11))", AstToStringBuilder.toString(originStmt));

        statementBase = SqlParser.parse(
                AstToStringBuilder.toString(originStmt), AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, statementBase.size());
        SetStmt convertStmt = (SetStmt) statementBase.get(0);
        Analyzer.analyze(convertStmt, AnalyzeTestUtil.getConnectContext());
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) convertStmt.getSetListItems().get(2));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) convertStmt.getSetListItems().get(3));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) convertStmt.getSetListItems().get(4));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) convertStmt.getSetListItems().get(5));
        SetStmtAnalyzer.calcuteUserVariable((UserVariable) convertStmt.getSetListItems().get(6));

        Assertions.assertEquals(7, convertStmt.getSetListItems().size());
        Assertions.assertEquals(SetType.SESSION, ((SystemVariable) convertStmt.getSetListItems().get(0)).getType());
        Assertions.assertEquals(SetType.SESSION, ((SystemVariable) convertStmt.getSetListItems().get(1)).getType());
        Assertions.assertEquals(AstToStringBuilder.toString(originStmt), AstToStringBuilder.toString(convertStmt));
    }

    @Test
    public void testReservedCteNameView() {
        String sql;
        sql = "CREATE VIEW abc AS ( \n" +
                "with `case` as (select 1 as c) SELECT v1 FROM t0 WHERE ((NOT false) IS NOT NULL));";
        List<StatementBase>
                statementBase =
                SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, statementBase.size());
        StatementBase baseStmt = statementBase.get(0);
        Analyzer.analyze(baseStmt, AnalyzeTestUtil.getConnectContext());
        Assertions.assertTrue(baseStmt instanceof CreateViewStmt);
        CreateViewStmt viewStmt = (CreateViewStmt) baseStmt;
        Assertions.assertEquals("(WITH `case` (`c`) AS (SELECT 1 AS `c`) SELECT `test`.`t0`.`v1`\n" +
                        "FROM `test`.`t0`\n" +
                        "WHERE (NOT FALSE) IS NOT NULL)", viewStmt.getInlineViewDef(), viewStmt.getInlineViewDef());
        statementBase = SqlParser.parse(sql, new SessionVariable());
        Assertions.assertEquals(1, statementBase.size());
    }

    @Test
    public void testSelectStarExcludeToString() throws Exception {
        String sql = "SELECT * EXCLUDE (name, email) FROM test_exclude;";

        List<StatementBase> stmts = 
                SqlParser.parse(sql, AnalyzeTestUtil.getConnectContext().getSessionVariable().getSqlMode());
        Assertions.assertEquals(1, stmts.size());

        StatementBase stmt = stmts.get(0);
        Assertions.assertTrue(stmt instanceof QueryStatement);
        QueryStatement queryStmt = (QueryStatement) stmt;

        Analyzer.analyze(queryStmt, AnalyzeTestUtil.getConnectContext());

        String expected = "SELECT * EXCLUDE ( `name`,`email` )  FROM test.test_exclude";
        String actual =  AstToStringBuilder.toString(queryStmt);

        Assertions.assertEquals(expected, actual);
    }
}
