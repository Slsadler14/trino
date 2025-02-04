/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.relational;

import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.metadata.FunctionManager;
import io.trino.metadata.Metadata;
import io.trino.metadata.ResolvedFunction;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.sql.ir.Arithmetic;
import io.trino.sql.ir.Between;
import io.trino.sql.ir.Bind;
import io.trino.sql.ir.Call;
import io.trino.sql.ir.Case;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.Coalesce;
import io.trino.sql.ir.Comparison;
import io.trino.sql.ir.Comparison.Operator;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.FieldReference;
import io.trino.sql.ir.In;
import io.trino.sql.ir.IrVisitor;
import io.trino.sql.ir.IsNull;
import io.trino.sql.ir.Lambda;
import io.trino.sql.ir.Logical;
import io.trino.sql.ir.Negation;
import io.trino.sql.ir.Not;
import io.trino.sql.ir.NullIf;
import io.trino.sql.ir.Reference;
import io.trino.sql.ir.Row;
import io.trino.sql.ir.Switch;
import io.trino.sql.ir.WhenClause;
import io.trino.sql.planner.Symbol;
import io.trino.sql.relational.SpecialForm.Form;
import io.trino.sql.relational.optimizer.ExpressionOptimizer;
import io.trino.type.TypeCoercion;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.metadata.GlobalFunctionCatalog.builtinFunctionName;
import static io.trino.spi.function.OperatorType.EQUAL;
import static io.trino.spi.function.OperatorType.HASH_CODE;
import static io.trino.spi.function.OperatorType.INDETERMINATE;
import static io.trino.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.trino.spi.function.OperatorType.NEGATION;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.trino.sql.relational.Expressions.call;
import static io.trino.sql.relational.Expressions.constant;
import static io.trino.sql.relational.Expressions.constantNull;
import static io.trino.sql.relational.Expressions.field;
import static io.trino.sql.relational.SpecialForm.Form.AND;
import static io.trino.sql.relational.SpecialForm.Form.BETWEEN;
import static io.trino.sql.relational.SpecialForm.Form.BIND;
import static io.trino.sql.relational.SpecialForm.Form.COALESCE;
import static io.trino.sql.relational.SpecialForm.Form.DEREFERENCE;
import static io.trino.sql.relational.SpecialForm.Form.IF;
import static io.trino.sql.relational.SpecialForm.Form.IN;
import static io.trino.sql.relational.SpecialForm.Form.IS_NULL;
import static io.trino.sql.relational.SpecialForm.Form.NULL_IF;
import static io.trino.sql.relational.SpecialForm.Form.OR;
import static io.trino.sql.relational.SpecialForm.Form.ROW_CONSTRUCTOR;
import static io.trino.sql.relational.SpecialForm.Form.SWITCH;
import static io.trino.sql.relational.SpecialForm.Form.WHEN;
import static java.util.Objects.requireNonNull;

public final class SqlToRowExpressionTranslator
{
    private SqlToRowExpressionTranslator() {}

    public static RowExpression translate(
            Expression expression,
            Map<Symbol, Integer> layout,
            Metadata metadata,
            FunctionManager functionManager,
            TypeManager typeManager,
            Session session,
            boolean optimize)
    {
        Visitor visitor = new Visitor(metadata, typeManager, layout);
        RowExpression result = visitor.process(expression, null);

        requireNonNull(result, "result is null");

        if (optimize) {
            ExpressionOptimizer optimizer = new ExpressionOptimizer(metadata, functionManager, session);
            return optimizer.optimize(result);
        }

        return result;
    }

    public static class Visitor
            extends IrVisitor<RowExpression, Void>
    {
        private final Metadata metadata;
        private final TypeCoercion typeCoercion;
        private final Map<Symbol, Integer> layout;
        private final StandardFunctionResolution standardFunctionResolution;

        protected Visitor(
                Metadata metadata,
                TypeManager typeManager,
                Map<Symbol, Integer> layout)
        {
            this.metadata = metadata;
            this.typeCoercion = new TypeCoercion(typeManager::getType);
            this.layout = layout;
            standardFunctionResolution = new StandardFunctionResolution(metadata);
        }

        @Override
        protected RowExpression visitExpression(Expression node, Void context)
        {
            throw new UnsupportedOperationException("not yet implemented: expression translator for " + node.getClass().getName());
        }

        @Override
        protected RowExpression visitConstant(Constant node, Void context)
        {
            return constant(node.value(), node.type());
        }

        @Override
        protected RowExpression visitComparison(Comparison node, Void context)
        {
            RowExpression left = process(node.left(), context);
            RowExpression right = process(node.right(), context);
            Operator operator = node.operator();

            switch (node.operator()) {
                case NOT_EQUAL:
                    return new CallExpression(
                            metadata.resolveBuiltinFunction("not", fromTypes(BOOLEAN)),
                            ImmutableList.of(visitComparisonExpression(Operator.EQUAL, left, right)));
                case GREATER_THAN:
                    return visitComparisonExpression(Operator.LESS_THAN, right, left);
                case GREATER_THAN_OR_EQUAL:
                    return visitComparisonExpression(Operator.LESS_THAN_OR_EQUAL, right, left);
                default:
                    return visitComparisonExpression(operator, left, right);
            }
        }

        private RowExpression visitComparisonExpression(Operator operator, RowExpression left, RowExpression right)
        {
            return call(
                    standardFunctionResolution.comparisonFunction(operator, left.getType(), right.getType()),
                    left,
                    right);
        }

        @Override
        protected RowExpression visitCall(Call node, Void context)
        {
            List<RowExpression> arguments = node.arguments().stream()
                    .map(value -> process(value, context))
                    .collect(toImmutableList());

            return new CallExpression(node.function(), arguments);
        }

        @Override
        protected RowExpression visitReference(Reference node, Void context)
        {
            Integer field = layout.get(Symbol.from(node));
            if (field != null) {
                return field(field, ((Expression) node).type());
            }

            return new VariableReferenceExpression(node.name(), ((Expression) node).type());
        }

        @Override
        protected RowExpression visitLambda(Lambda node, Void context)
        {
            return new LambdaDefinitionExpression(
                    node.arguments(),
                    process(node.body(), context));
        }

        @Override
        protected RowExpression visitBind(Bind node, Void context)
        {
            ImmutableList.Builder<Type> valueTypesBuilder = ImmutableList.builder();
            ImmutableList.Builder<RowExpression> argumentsBuilder = ImmutableList.builder();
            for (Expression value : node.values()) {
                RowExpression valueRowExpression = process(value, context);
                valueTypesBuilder.add(valueRowExpression.getType());
                argumentsBuilder.add(valueRowExpression);
            }
            RowExpression function = process(node.function(), context);
            argumentsBuilder.add(function);

            return new SpecialForm(BIND, ((Expression) node).type(), argumentsBuilder.build());
        }

        @Override
        protected RowExpression visitArithmetic(Arithmetic node, Void context)
        {
            RowExpression left = process(node.left(), context);
            RowExpression right = process(node.right(), context);

            return call(
                    standardFunctionResolution.arithmeticFunction(node.operator(), left.getType(), right.getType()),
                    left,
                    right);
        }

        @Override
        protected RowExpression visitNegation(Negation node, Void context)
        {
            RowExpression expression = process(node.value(), context);
            return call(
                    metadata.resolveOperator(NEGATION, ImmutableList.of(expression.getType())),
                    expression);
        }

        @Override
        protected RowExpression visitLogical(Logical node, Void context)
        {
            Form form;
            switch (node.operator()) {
                case AND:
                    form = AND;
                    break;
                case OR:
                    form = OR;
                    break;
                default:
                    throw new IllegalStateException("Unknown logical operator: " + node.operator());
            }
            return new SpecialForm(
                    form,
                    BOOLEAN,
                    node.terms().stream()
                            .map(term -> process(term, context))
                            .collect(toImmutableList()));
        }

        @Override
        protected RowExpression visitCast(Cast node, Void context)
        {
            RowExpression value = process(node.expression(), context);

            Type returnType = ((Expression) node).type();
            if (typeCoercion.isTypeOnlyCoercion(value.getType(), returnType)) {
                return changeType(value, returnType);
            }

            if (node.safe()) {
                return call(
                        metadata.getCoercion(builtinFunctionName("TRY_CAST"), value.getType(), returnType),
                        value);
            }

            return call(
                    metadata.getCoercion(value.getType(), returnType),
                    value);
        }

        private static RowExpression changeType(RowExpression value, Type targetType)
        {
            ChangeTypeVisitor visitor = new ChangeTypeVisitor(targetType);
            return value.accept(visitor, null);
        }

        private static class ChangeTypeVisitor
                implements RowExpressionVisitor<RowExpression, Void>
        {
            private final Type targetType;

            private ChangeTypeVisitor(Type targetType)
            {
                this.targetType = targetType;
            }

            @Override
            public RowExpression visitCall(CallExpression call, Void context)
            {
                return new CallExpression(call.getResolvedFunction(), call.getArguments());
            }

            @Override
            public RowExpression visitSpecialForm(SpecialForm specialForm, Void context)
            {
                return new SpecialForm(specialForm.getForm(), targetType, specialForm.getArguments(), specialForm.getFunctionDependencies());
            }

            @Override
            public RowExpression visitInputReference(InputReferenceExpression reference, Void context)
            {
                return field(reference.getField(), targetType);
            }

            @Override
            public RowExpression visitConstant(ConstantExpression literal, Void context)
            {
                return constant(literal.getValue(), targetType);
            }

            @Override
            public RowExpression visitLambda(LambdaDefinitionExpression lambda, Void context)
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public RowExpression visitVariableReference(VariableReferenceExpression reference, Void context)
            {
                return new VariableReferenceExpression(reference.getName(), targetType);
            }
        }

        @Override
        protected RowExpression visitCoalesce(Coalesce node, Void context)
        {
            List<RowExpression> arguments = node.operands().stream()
                    .map(value -> process(value, context))
                    .collect(toImmutableList());

            return new SpecialForm(COALESCE, ((Expression) node).type(), arguments);
        }

        @Override
        protected RowExpression visitSwitch(Switch node, Void context)
        {
            ImmutableList.Builder<RowExpression> arguments = ImmutableList.builder();

            RowExpression value = process(node.operand(), context);
            arguments.add(value);

            ImmutableList.Builder<ResolvedFunction> functionDependencies = ImmutableList.builder();
            for (WhenClause clause : node.whenClauses()) {
                RowExpression operand = process(clause.getOperand(), context);
                RowExpression result = process(clause.getResult(), context);

                functionDependencies.add(metadata.resolveOperator(EQUAL, ImmutableList.of(value.getType(), operand.getType())));

                arguments.add(new SpecialForm(
                        WHEN,
                        clause.getResult().type(),
                        operand,
                        result));
            }

            Type returnType = ((Expression) node).type();

            arguments.add(node.defaultValue()
                    .map(defaultValue -> process(defaultValue, context))
                    .orElse(constantNull(returnType)));

            return new SpecialForm(SWITCH, returnType, arguments.build(), functionDependencies.build());
        }

        @Override
        protected RowExpression visitCase(Case node, Void context)
        {
            /*
                Translates an expression like:

                    case when cond1 then value1
                         when cond2 then value2
                         when cond3 then value3
                         else value4
                    end

                To:

                    IF(cond1,
                        value1,
                        IF(cond2,
                            value2,
                                If(cond3,
                                    value3,
                                    value4)))

             */
            RowExpression expression = node.defaultValue()
                    .map(value -> process(value, context))
                    .orElse(constantNull(((Expression) node).type()));

            for (WhenClause clause : node.whenClauses().reversed()) {
                expression = new SpecialForm(
                        IF,
                        ((Expression) node).type(),
                        process(clause.getOperand(), context),
                        process(clause.getResult(), context),
                        expression);
            }

            return expression;
        }

        @Override
        protected RowExpression visitIn(In node, Void context)
        {
            ImmutableList.Builder<RowExpression> arguments = ImmutableList.builder();
            RowExpression value = process(node.value(), context);
            arguments.add(value);
            for (Expression testValue : node.valueList()) {
                arguments.add(process(testValue, context));
            }

            List<ResolvedFunction> functionDependencies = ImmutableList.<ResolvedFunction>builder()
                    .add(metadata.resolveOperator(EQUAL, ImmutableList.of(value.getType(), value.getType())))
                    .add(metadata.resolveOperator(HASH_CODE, ImmutableList.of(value.getType())))
                    .add(metadata.resolveOperator(INDETERMINATE, ImmutableList.of(value.getType())))
                    .build();

            return new SpecialForm(IN, BOOLEAN, arguments.build(), functionDependencies);
        }

        @Override
        protected RowExpression visitIsNull(IsNull node, Void context)
        {
            RowExpression expression = process(node.value(), context);

            return new SpecialForm(IS_NULL, BOOLEAN, expression);
        }

        @Override
        protected RowExpression visitNot(Not node, Void context)
        {
            return notExpression(process(node.value(), context));
        }

        private RowExpression notExpression(RowExpression value)
        {
            return new CallExpression(
                    metadata.resolveBuiltinFunction("not", fromTypes(BOOLEAN)),
                    ImmutableList.of(value));
        }

        @Override
        protected RowExpression visitNullIf(NullIf node, Void context)
        {
            RowExpression first = process(node.first(), context);
            RowExpression second = process(node.second(), context);

            ResolvedFunction resolvedFunction = metadata.resolveOperator(EQUAL, ImmutableList.of(first.getType(), second.getType()));
            List<ResolvedFunction> functionDependencies = ImmutableList.<ResolvedFunction>builder()
                    .add(resolvedFunction)
                    .add(metadata.getCoercion(first.getType(), resolvedFunction.getSignature().getArgumentTypes().get(0)))
                    .add(metadata.getCoercion(second.getType(), resolvedFunction.getSignature().getArgumentTypes().get(0)))
                    .build();

            return new SpecialForm(
                    NULL_IF,
                    ((Expression) node).type(),
                    ImmutableList.of(first, second),
                    functionDependencies);
        }

        @Override
        protected RowExpression visitBetween(Between node, Void context)
        {
            RowExpression value = process(node.value(), context);
            RowExpression min = process(node.min(), context);
            RowExpression max = process(node.max(), context);

            List<ResolvedFunction> functionDependencies = ImmutableList.of(
                    metadata.resolveOperator(LESS_THAN_OR_EQUAL, ImmutableList.of(value.getType(), max.getType())));

            return new SpecialForm(
                    BETWEEN,
                    BOOLEAN,
                    ImmutableList.of(value, min, max),
                    functionDependencies);
        }

        @Override
        protected RowExpression visitFieldReference(FieldReference node, Void context)
        {
            RowExpression base = process(node.base(), context);
            return new SpecialForm(DEREFERENCE, node.type(), base, constant((long) node.field(), INTEGER));
        }

        @Override
        protected RowExpression visitRow(Row node, Void context)
        {
            List<RowExpression> arguments = node.items().stream()
                    .map(value -> process(value, context))
                    .collect(toImmutableList());
            Type returnType = ((Expression) node).type();
            return new SpecialForm(ROW_CONSTRUCTOR, returnType, arguments);
        }
    }
}
