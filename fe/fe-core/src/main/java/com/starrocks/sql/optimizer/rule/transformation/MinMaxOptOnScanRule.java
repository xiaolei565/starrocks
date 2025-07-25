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

package com.starrocks.sql.optimizer.rule.transformation;

import com.starrocks.catalog.AggregateFunction;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalAggregationOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalScanOperator;
import com.starrocks.sql.optimizer.operator.pattern.MultiOpPattern;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// for a simple min/max/count aggregation query like
// 'select min(c1),max(c2) from table',
// we add a label on scan node to indicates that pattern for further optimization

public class MinMaxOptOnScanRule extends TransformationRule {
    private static final Set<OperatorType> SUPPORTED = Set.of(
            OperatorType.LOGICAL_ICEBERG_SCAN
    );

    public MinMaxOptOnScanRule() {
        // agg -> project -> iceberg scan
        super(RuleType.TF_REWRITE_MIN_MAX_COUNT_AGG,
                Pattern.create(OperatorType.LOGICAL_AGGR).
                        addChildren(Pattern.create(OperatorType.LOGICAL_PROJECT).
                                addChildren(MultiOpPattern.of(SUPPORTED))));
    }

    @Override
    public boolean check(final OptExpression input, OptimizerContext context) {
        if (!context.getSessionVariable().isEnableMinMaxOptimization()) {
            return false;
        }
        LogicalAggregationOperator aggregationOperator = (LogicalAggregationOperator) input.getOp();
        Operator operator = input.getInputs().get(0).getInputs().get(0).getOp();
        LogicalScanOperator scanOperator = (LogicalScanOperator) operator;

        // we can only apply this rule to the queries met all the following conditions:
        // 1. no group by key (only partition columns)
        // 2. no `having` condition or other filters
        // 3. no limit(???)
        // 4. only contain MIN/MAX agg functions
        // 5. all arguments to agg functions are primitive columns (not necessarily)
        // 6. no expr in arguments to agg functions

        // no limit
        if (scanOperator.getLimit() != -1) {
            return false;
        }

        // no materialized column in predicate of scan
        if (hasMaterializedColumnInPredicate(scanOperator, scanOperator.getPredicate())) {
            return false;
        }

        List<ColumnRefOperator> groupingKeys = aggregationOperator.getGroupingKeys();
        if (groupingKeys != null && !groupingKeys.isEmpty()) {
            // all group by keys are partition keys.
            if (!scanOperator.getPartitionColumns()
                    .containsAll(groupingKeys.stream().map(x -> x.getName()).collect(Collectors.toList()))) {
                return false;
            }
        }

        // no materialized column in predicate of aggregation
        if (hasMaterializedColumnInPredicate(scanOperator, aggregationOperator.getPredicate())) {
            return false;
        }

        boolean allValid = aggregationOperator.getAggregations().values().stream().allMatch(aggregator -> {
            AggregateFunction aggregateFunction = (AggregateFunction) aggregator.getFunction();
            String functionName = aggregateFunction.functionName();

            // min/max/
            if (!(functionName.equals(FunctionSet.MAX) || functionName.equals(FunctionSet.MIN))) {
                return false;
            }

            // one argument which is slot ref.
            List<ScalarOperator> arguments = aggregator.getArguments();
            if (arguments == null || arguments.size() != 1) {
                return false;
            }
            ScalarOperator arg = arguments.get(0);
            if (!arg.isColumnRef()) {
                return false;
            }
            return true;
        });
        return allValid;
    }

    private static boolean hasMaterializedColumnInPredicate(LogicalScanOperator scanOperator, ScalarOperator predicate) {
        if (predicate == null) {
            return false;
        }
        List<ColumnRefOperator> columnRefOperators = predicate.getColumnRefs();
        Set<String> partitionColumns = scanOperator.getPartitionColumns();
        for (ColumnRefOperator c : columnRefOperators) {
            if (!partitionColumns.contains(c.getName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalScanOperator scanOperator = (LogicalScanOperator) input.getInputs().get(0).getInputs().get(0).getOp();
        scanOperator.getScanOptimizeOption().setCanUseMinMaxOpt(true);
        return Collections.emptyList();
    }
}
