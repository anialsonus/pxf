/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.	See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership. The
 * ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */

/*
 * pxf_filter.c
 *
 * Functions for handling push down of supported scan level filters to PXF.
 */
#include "pxf_filter.h"

#include "catalog/pg_operator.h"
#include "optimizer/clauses.h"
#include "parser/parse_expr.h"
#include "utils/builtins.h"
#include "utils/guc.h"
#include "utils/lsyscache.h"
#include "utils/typcache.h"

static List *PxfMakeExpressionItemsList(List *quals, Node *parent);
static void PxfFreeFilter(PxfFilterDesc *filter);
static char *PxfSerializeFilterList(List *filters);
static bool OpExprToPxfFilter(OpExpr *expr, PxfFilterDesc *filter);
static bool
			ScalarArrayOpExprToPxfFilter(ScalarArrayOpExpr *expr, PxfFilterDesc *filter);
static bool VarToPxfFilter(Var *var, PxfFilterDesc *filter);
static bool SupportedFilterType(Oid type);
static bool SupportedOperatorTypeOpExpr(Oid type, PxfFilterDesc *filter);
static bool SupportedOperatorTypeScalarArrayOpExpr(Oid type,
												   PxfFilterDesc *filter,
												   bool useOr);
static void ScalarConstToStr(Const *constval, StringInfo buf);
static void ListConstToStr(Const *constval, StringInfo buf, bool with_nulls);
static List *AppendAttrFromVar(Var *var, List *attrs);
static void AddExtraAndExpressionItems(List *expressionItems,
									   int extraAndOperatorsNum);
static List *GetAttrsFromExpr(Expr *expr, bool *expressionIsSupported);
static bool SupportedArrayType(Oid type);

/*
 * All supported operators and their PXF operator codes.
 * Note that it is OK to use hardcoded OIDs, since these are all pinned
 * down system catalog operators.
 * See catalog/pg_operator.h in Greenplum 6 or catalog/pg_operator_d.h in Greenplum 7
 */
dbop_pxfop_map pxf_supported_opr_op_expr[] =
{
	/* int2 */
	{Int2EqualOperator /* int2eq */ , PXFOP_EQ},
	{95 /* int2lt */ , PXFOP_LT},
	{520 /* int2gt */ , PXFOP_GT},
	{522 /* int2le */ , PXFOP_LE},
	{524 /* int2ge */ , PXFOP_GE},
	{519 /* int2ne */ , PXFOP_NE},

	/* int4 */
	{Int4EqualOperator /* int4eq */ , PXFOP_EQ},
	{97 /* int4lt */ , PXFOP_LT},
	{521 /* int4gt */ , PXFOP_GT},
	{523 /* int4le */ , PXFOP_LE},
	{525 /* int4ge */ , PXFOP_GE},
	{518 /* int4lt */ , PXFOP_NE},

	/* int8 */
	{Int8EqualOperator /* int8eq */ , PXFOP_EQ},
	{412 /* int8lt */ , PXFOP_LT},
	{413 /* int8gt */ , PXFOP_GT},
	{414 /* int8le */ , PXFOP_LE},
	{415 /* int8ge */ , PXFOP_GE},
	{411 /* int8lt */ , PXFOP_NE},

	/* text */
	{TextEqualOperator /* texteq  */ , PXFOP_EQ},
	{664 /* text_lt */ , PXFOP_LT},
	{666 /* text_gt */ , PXFOP_GT},
	{665 /* text_le */ , PXFOP_LE},
	{667 /* text_ge */ , PXFOP_GE},
	{531 /* textlt	*/ , PXFOP_NE},
	{1209 /* textlike  */ , PXFOP_LIKE},
	{1210 /* textnlike  */ , PXFOP_NOTLIKE},

	/* int2 to int4 */
	{Int24EqualOperator /* int24eq */ , PXFOP_EQ},
	{534 /* int24lt */ , PXFOP_LT},
	{536 /* int24gt */ , PXFOP_GT},
	{540 /* int24le */ , PXFOP_LE},
	{542 /* int24ge */ , PXFOP_GE},
	{538 /* int24ne */ , PXFOP_NE},

	/* int4 to int2 */
	{Int42EqualOperator /* int42eq */ , PXFOP_EQ},
	{535 /* int42lt */ , PXFOP_LT},
	{537 /* int42gt */ , PXFOP_GT},
	{541 /* int42le */ , PXFOP_LE},
	{543 /* int42ge */ , PXFOP_GE},
	{539 /* int42ne */ , PXFOP_NE},

	/* int8 to int4 */
	{Int84EqualOperator /* int84eq */ , PXFOP_EQ},
	{418 /* int84lt */ , PXFOP_LT},
	{419 /* int84gt */ , PXFOP_GT},
	{420 /* int84le */ , PXFOP_LE},
	{430 /* int84ge */ , PXFOP_GE},
	{417 /* int84ne */ , PXFOP_NE},

	/* int4 to int8 */
	{Int48EqualOperator /* int48eq */ , PXFOP_EQ},
	{37 /* int48lt */ , PXFOP_LT},
	{76 /* int48gt */ , PXFOP_GT},
	{80 /* int48le */ , PXFOP_LE},
	{82 /* int48ge */ , PXFOP_GE},
	{36 /* int48ne */ , PXFOP_NE},

	/* int2 to int8 */
	{Int28EqualOperator /* int28eq */ , PXFOP_EQ},
	{1864 /* int28lt */ , PXFOP_LT},
	{1865 /* int28gt */ , PXFOP_GT},
	{1866 /* int28le */ , PXFOP_LE},
	{1867 /* int28ge */ , PXFOP_GE},
	{1863 /* int28ne */ , PXFOP_NE},

	/* int8 to int2 */
	{Int82EqualOperator /* int82eq */ , PXFOP_EQ},
	{1870 /* int82lt */ , PXFOP_LT},
	{1871 /* int82gt */ , PXFOP_GT},
	{1872 /* int82le */ , PXFOP_LE},
	{1873 /* int82ge */ , PXFOP_GE},
	{1869 /* int82ne */ , PXFOP_NE},

	/* date */
	{DateEqualOperator /* date_eq */ , PXFOP_EQ},
	{1095 /* date_lt */ , PXFOP_LT},
	{1097 /* date_gt */ , PXFOP_GT},
	{1096 /* date_le */ , PXFOP_LE},
	{1098 /* date_ge */ , PXFOP_GE},
	{1094 /* date_ne */ , PXFOP_NE},

	/* timestamp */
	{TimestampEqualOperator /* timestamp_eq */ , PXFOP_EQ},
	{2062 /* timestamp_lt */ , PXFOP_LT},
	{2064 /* timestamp_gt */ , PXFOP_GT},
	{2063 /* timestamp_le */ , PXFOP_LE},
	{2065 /* timestamp_ge */ , PXFOP_GE},
	{2061 /* timestamp_ne */ , PXFOP_NE},

	/* float8 */
	{Float8EqualOperator /* float8eq */ , PXFOP_EQ},
	{672 /* float8lt */ , PXFOP_LT},
	{674 /* float8gt */ , PXFOP_GT},
	{673 /* float8le */ , PXFOP_LE},
	{675 /* float8ge */ , PXFOP_GE},
	{671 /* float8ne */ , PXFOP_NE},

	/* float48 */
	{1120 /* float48eq */ , PXFOP_EQ},
	{1122 /* float48lt */ , PXFOP_LT},
	{1123 /* float48gt */ , PXFOP_GT},
	{1124 /* float48le */ , PXFOP_LE},
	{1125 /* float48ge */ , PXFOP_GE},
	{1121 /* float48ne */ , PXFOP_NE},

	/* float84 */
	{1130 /* float84eq */ , PXFOP_EQ},
	{1132 /* float84lt */ , PXFOP_LT},
	{1133 /* float84gt */ , PXFOP_GT},
	{1134 /* float84le */ , PXFOP_LE},
	{1135 /* float84ge */ , PXFOP_GE},
	{1131 /* float84ne */ , PXFOP_NE},

	/* float4 */
	{Float4EqualOperator /* float4eq */ , PXFOP_EQ},
	{622 /* float4lt */ , PXFOP_LT},
	{623 /* float4gt */ , PXFOP_GT},
	{624 /* float4le */ , PXFOP_LE},
	{625 /* float4ge */ , PXFOP_GE},
	{621 /* float4ne */ , PXFOP_NE},

	/* boolean */
	{BooleanEqualOperator /* booleq */ , PXFOP_EQ},
	{58 /* boollt */ , PXFOP_LT},
	{59 /* boolgt */ , PXFOP_GT},
	{1694 /* boolle */ , PXFOP_LE},
	{1695 /* boolge */ , PXFOP_GE},
	{85 /* boolne */ , PXFOP_NE},

	/* bpchar */
	{BPCharEqualOperator /* bpchareq */ , PXFOP_EQ},
	{1058 /* bpcharlt */ , PXFOP_LT},
	{1060 /* bpchargt */ , PXFOP_GT},
	{1059 /* bpcharle */ , PXFOP_LE},
	{1061 /* bpcharge */ , PXFOP_GE},
	{1057 /* bpcharne */ , PXFOP_NE},
	{1211 /* bpcharlike */ , PXFOP_LIKE},
	{1212 /* bpcharnlike */ , PXFOP_NOTLIKE},

	/* numeric */
	{NumericEqualOperator /* numericeq */ , PXFOP_EQ},
	{1754 /* numericlt */ , PXFOP_LT},
	{1756 /* numericgt */ , PXFOP_GT},
	{1755 /* numericle */ , PXFOP_LE},
	{1757 /* numericge */ , PXFOP_GE},
	{1753 /* numericne */ , PXFOP_NE},

	/* generic array comparison operators */
	{ARRAY_EQ_OP /* array_eq */, PXFOP_EQ},
	{1071 /*array_ne */, PXFOP_NE},

	/* bytea */
	{ByteaEqualOperator /* byteaeq */ , PXFOP_EQ},
	{1957 /* bytealt */ , PXFOP_LT},
	{1959 /* byteagt */ , PXFOP_GT},
	{1958 /* byteale */ , PXFOP_LE},
	{1960 /* byteage */ , PXFOP_GE},
	{1956 /* byteane */ , PXFOP_NE},
	{2016 /* bytealike */ , PXFOP_LIKE},
	{2017 /* byteanlike */ , PXFOP_NOTLIKE},

	/* time */
	{TimeEqualOperator /* time_eq */ , PXFOP_EQ},
	{1110 /* time_lt */ , PXFOP_LT},
	{1112 /* time_gt */ , PXFOP_GT},
	{1111 /* time_le */ , PXFOP_LE},
	{1113 /* time_ge */ , PXFOP_GE},
	{1109 /* time_ne */ , PXFOP_NE},

	/* timestamp with time zone */
	{TimestampTZEqualOperator /* timestamptz_eq */ , PXFOP_EQ},
	{1322 /* timestamptz_lt */ , PXFOP_LT},
	{1324 /* timestamptz_gt */ , PXFOP_GT},
	{1323 /* timestamptz_le */ , PXFOP_LE},
	{1325 /* timestamptz_ge */ , PXFOP_GE},
	{1321 /* timestamptz_ne */ , PXFOP_NE},

	/* interval */
	{IntervalEqualOperator /* interval_eq */ , PXFOP_EQ},
	{1332 /* interval_lt */ , PXFOP_LT},
	{1334 /* interval_gt */ , PXFOP_GT},
	{1333 /* interval_le */ , PXFOP_LE},
	{1335 /* interval_ge */ , PXFOP_GE},
	{1331 /* interval_ne */ , PXFOP_NE},

	/* uuid */
	{2972 /* uuid_eq */ , PXFOP_EQ},
	{2974 /* uuid_lt */ , PXFOP_LT},
	{2975 /* uuid_gt */ , PXFOP_GT},
	{2976 /* uuid_le */ , PXFOP_LE},
	{2977 /* uuid_ge */ , PXFOP_GE},
	{2973 /* uuid_ne */ , PXFOP_NE},

	/* jsonb */
	{3240 /* jsonb_eq */ , PXFOP_EQ},
	{3242 /* jsonb_lt */ , PXFOP_LT},
	{3243 /* jsonb_gt */ , PXFOP_GT},
	{3244 /* jsonb_le */ , PXFOP_LE},
	{3245 /* jsonb_ge */ , PXFOP_GE},
	{3241 /* jsonb_ne */ , PXFOP_NE},
};

dbop_pxfop_array_map pxf_supported_opr_scalar_array_op_expr[] =
{
	/* int2 */
	{Int2EqualOperator /* int2eq */ , PXFOP_IN, true},
	{519 /* int2ne */ , PXFOP_NOTIN, false},

	/* int4 */
	{Int4EqualOperator /* int4eq */ , PXFOP_IN, true},
	{518 /* int4ne */ , PXFOP_NOTIN, false},

	/* int8 */
	{Int8EqualOperator /* int8eq */ , PXFOP_IN, true},
	{411 /* int8ne */ , PXFOP_NOTIN, false},

	/* text */
	{TextEqualOperator /* texteq  */ , PXFOP_IN, true},
	{531 /* textne */ , PXFOP_NOTIN, false},

	/* int2 to int4 */
	{Int24EqualOperator /* int24eq */ , PXFOP_IN,
	true},
	{538 /* int24ne */ , PXFOP_NOTIN, false},

	/* int4 to int2 */
	{Int42EqualOperator /* int42eq */ , PXFOP_IN,
	true},
	{539 /* int42ne */ , PXFOP_NOTIN, false},

	/* int8 to int4 */
	{Int84EqualOperator /* int84eq */ , PXFOP_IN,
	true},
	{417 /* int84ne */ , PXFOP_NOTIN, false},

	/* int4 to int8 */
	{Int48EqualOperator /* int48eq */ , PXFOP_IN,
	true},
	{36 /* int48ne */ , PXFOP_NOTIN, false},

	/* int2 to int8 */
	{Int28EqualOperator /* int28eq */ , PXFOP_IN,
	true},
	{1863 /* int28ne */ , PXFOP_NOTIN, false},

	/* int8 to int2 */
	{Int82EqualOperator /* int82eq */ , PXFOP_IN,
	true},
	{1869 /* int82ne */ , PXFOP_NOTIN, false},

	/* date */
	{DateEqualOperator /* date_eq */ , PXFOP_IN, true},
	{1094 /* date_ne */ , PXFOP_NOTIN, false},

	/* timestamp */
	{TimestampEqualOperator /* timestamp_eq */ ,
	PXFOP_IN, true},
	{2061 /* date_ne */ , PXFOP_NOTIN, false},

	/* float8 */
	{Float8EqualOperator /* float8eq */ , PXFOP_IN,
	true},
	{671 /* float8ne */ , PXFOP_NOTIN, false},

	/* float48 */
	{1120 /* float48eq */ , PXFOP_IN, true},
	{1121 /* float48ne */ , PXFOP_NOTIN, false},

	/* float84 */
	{1130 /* float84eq */ , PXFOP_IN, true},
	{1131 /* float84ne */ , PXFOP_NOTIN, false},

	/* float4 */
	{Float4EqualOperator /* float4eq */ , PXFOP_IN, true},
	{621 /* float4ne */ , PXFOP_NOTIN, false},

	/* bpchar */
	{BPCharEqualOperator /* bpchareq */ , PXFOP_IN,
	true},
	{1057 /* bpcharne */ , PXFOP_NOTIN, false},

	{BooleanEqualOperator /* booleq */ , PXFOP_IN, true},
	{85 /* boolne */ , PXFOP_NOTIN, false},

	{ByteaEqualOperator /* byteaeq */ , PXFOP_IN, true},
	{1956 /* byteane */ , PXFOP_NOTIN, false},

	{TimeEqualOperator /* time_eq */ , PXFOP_IN, true},
	{1109 /* time_ne */ , PXFOP_NOTIN, false},

	{TimestampTZEqualOperator /* time_eq */ , PXFOP_IN, true},
	{1321 /* timestamptz_ne */ , PXFOP_NOTIN, false},

	{IntervalEqualOperator /* interval_eq */ , PXFOP_IN, true},
	{1331 /* interval_ne */ , PXFOP_NOTIN, false},

	{NumericEqualOperator /* numericeq */ , PXFOP_IN, true},
	{1753 /* numericne */ , PXFOP_NOTIN, false},

	{2972 /* uuid_eq */ , PXFOP_IN, true},
	{2973 /* uuid_ne */ , PXFOP_NOTIN, false},

	{3240 /* jsonb_eq */ , PXFOP_IN, true},
	{3241 /* jsonb_ne */ , PXFOP_NOTIN, false},
};

/*
 * In GPDB 6 the following array macros are not defined.
 */
#ifndef BOOLARRAYOID
#define BOOLARRAYOID 1000
#endif

#ifndef BYTEAARRAYOID
#define BYTEAARRAYOID 1001
#endif

#ifndef BPCHARARRAYOID
#define BPCHARARRAYOID 1014
#endif

#ifndef VARCHARARRAYOID
#define VARCHARARRAYOID 1015
#endif

#ifndef DATEARRAYOID
#define DATEARRAYOID 1182
#endif

#ifndef TIMEARRAYOID
#define TIMEARRAYOID 1183
#endif

#ifndef TIMESTAMPARRAYOID
#define TIMESTAMPARRAYOID 1115
#endif

#ifndef TIMESTAMPTZARRAYOID
#define TIMESTAMPTZARRAYOID 1185
#endif

#ifndef INTERVALARRAYOID
#define INTERVALARRAYOID 1187
#endif

#ifndef NUMERICARRAYOID
#define NUMERICARRAYOID 1231
#endif

#ifndef UUIDARRAYOID
#define UUIDARRAYOID 2951
#endif

#ifndef JSONBARRAYOID
#define JSONBARRAYOID 3807
#endif

#ifndef JSONARRAYOID
#define JSONARRAYOID 199
#endif

Oid			pxf_supported_types[] =
{
	INT2OID,
	INT4OID,
	INT8OID,
	FLOAT4OID,
	FLOAT8OID,
	NUMERICOID,
	BOOLOID,
	TEXTOID,
	VARCHAROID,
	BPCHAROID,
	CHAROID,
	DATEOID,
	TIMESTAMPOID,
	BYTEAOID,
	TIMEOID,
	TIMESTAMPTZOID,
	INTERVALOID,
	UUIDOID,
	JSONBOID,
	JSONOID,
	/* complex datatypes */
	INT2ARRAYOID,
	INT4ARRAYOID,
	INT8ARRAYOID,
	TEXTARRAYOID,
	BOOLARRAYOID,
	BYTEAARRAYOID,
	FLOAT4ARRAYOID,
	FLOAT8ARRAYOID,
	BPCHARARRAYOID,
	VARCHARARRAYOID,
	DATEARRAYOID,
	TIMEARRAYOID,
	TIMESTAMPARRAYOID,
	TIMESTAMPTZARRAYOID,
	INTERVALARRAYOID,
	NUMERICARRAYOID,
	UUIDARRAYOID,
	JSONBARRAYOID,
	JSONARRAYOID,
};

static Oid		pxf_supported_array_types[] =
{
	INT2ARRAYOID,
	INT4ARRAYOID,
	INT8ARRAYOID,
	TEXTARRAYOID,
	BOOLARRAYOID,
	BYTEAARRAYOID,
	FLOAT4ARRAYOID,
	FLOAT8ARRAYOID,
	BPCHARARRAYOID,
	VARCHARARRAYOID,
	DATEARRAYOID,
	TIMEARRAYOID,
	TIMESTAMPARRAYOID,
	TIMESTAMPTZARRAYOID,
	INTERVALARRAYOID,
	NUMERICARRAYOID,
	UUIDARRAYOID,
	JSONBARRAYOID,
	JSONARRAYOID,
};

static void
PxfFreeExpressionItemsList(List *expressionItems)
{
	ExpressionItem *expressionItem = NULL;

	while (list_length(expressionItems) > 0)
	{
		expressionItem = (ExpressionItem *) linitial(expressionItems);
		pfree(expressionItem);

		/*
		 * to avoid freeing already freed items - delete all occurrences of
		 * current expression
		 */
		int			previousLength = expressionItems->length + 1;

		while (expressionItems != NULL &&
			   previousLength > expressionItems->length)
		{
			previousLength = expressionItems->length;
			expressionItems = list_delete_ptr(expressionItems, expressionItem);
		}
	}
}

/*
 * PxfMakeExpressionItemsList
 *
 * Given a scan node qual list, find the filters that are eligible to be used
 * by PXF, construct an expressions list, which consists of OpExpr or BoolExpr nodes
 * and return it to the caller.
 *
 * Basically this function just transforms expression tree to Reversed Polish Notation list.
 *
 *
 */
static List *
PxfMakeExpressionItemsList(List *quals, Node *parent)
{
	ExpressionItem *expressionItem = NULL;
	List	   *result = NIL;
	ListCell   *lc = NULL;
	ListCell   *ilc = NULL;

	int			quals_size = list_length(quals);

	if (quals_size == 0)
		return NIL;

	foreach(lc, quals)
	{
		expressionItem = (ExpressionItem *) palloc0(sizeof(ExpressionItem));
		Node	   *node = (Node *) lfirst(lc);

		expressionItem->node = node;
		expressionItem->parent = parent;
		expressionItem->processed = false;

		NodeTag		tag = nodeTag(node);

		elog(DEBUG1, "PxfMakeExpressionItemsList: found NodeTag %d", tag);
		switch (tag)
		{
			case T_Var:
				/* IN(single_value) */
			case T_OpExpr:
				/* Comparison operators >,>=,=,etc */
			case T_ScalarArrayOpExpr:
			case T_NullTest:
				{
					result = lappend(result, expressionItem);
					break;
				}
			case T_BoolExpr:
				/* Logical operators AND, OR, NOT */
				{
					BoolExpr   *expr = (BoolExpr *) node;

					elog(DEBUG1,
						 "PxfMakeExpressionItemsList: found T_BoolExpr; make recursive call");
					List	   *inner_result =
					PxfMakeExpressionItemsList(expr->args, node);

					elog(DEBUG1,
						 "PxfMakeExpressionItemsList: recursive call end");

					result = list_concat(result, inner_result);

					int			childNodesNum = 0;

					/* Find number of child nodes on first level */
					foreach(ilc, inner_result)
					{
						ExpressionItem *ei = (ExpressionItem *) lfirst(ilc);

						if (!ei->processed && ei->parent == node)
						{
							ei->processed = true;
							childNodesNum++;
						}
					}

					if (expr->boolop == NOT_EXPR)
					{
						for (int i = 0; i < childNodesNum; i++)
						{
							result = lappend(result, expressionItem);
						}
					}
					else if (expr->boolop == AND_EXPR || expr->boolop == OR_EXPR)
					{
						for (int i = 0; i < childNodesNum - 1; i++)
						{
							result = lappend(result, expressionItem);
						}
					}
					else
					{
						elog(ERROR,
							 "internal error in pxffilters.c:PxfMakeExpressionItemsList. "
							 "Found unknown boolean expression type");
					}
					break;
				}
			default:
				elog(DEBUG1,
					 "PxfMakeExpressionItemsList: unsupported node tag %d",
					 tag);
				break;
		}
	}
	if (quals_size > 1 && parent == NULL)
	{
		/*
		 * Planner (but not ORCA) will omit AND operators at the root level,
		 * so if we find more than 1 qualifier,
		 */
		/*
		 * it means we are looking at 2 or more expressions that are
		 * implicitly AND-ed by the planner.
		 */
		/*
		 * Here, to make it explicit, we will need to add additional AND
		 * operators to compensate for the missing ones.
		 */
		AddExtraAndExpressionItems(result, quals_size - 1);
	}

	return result;
}

static void
PxfFreeFilter(PxfFilterDesc *filter)
{
	if (!filter)
		return;

	if (filter->l.conststr)
	{
		if (filter->l.conststr->data)
			pfree(filter->l.conststr->data);
		pfree(filter->l.conststr);
	}
	if (filter->r.conststr)
	{
		if (filter->r.conststr->data)
			pfree(filter->r.conststr->data);
		pfree(filter->r.conststr);
	}

	pfree(filter);
}

/*
* PxfSerializeFilterList
*
* Takes expression items list in RPN notation, produce a
* serialized string representation in order to communicate this list
* over the wire.
*
* The serialized string is in a RPN (Reversed Polish Notation) format
* as flattened tree. Operands and operators are represented with their
* respective codes. Each filter is serialized as follows:
*
* <attcode><attnum><constcode><constval><constsizecode><constsize><constdata><constvalue><opercode><opernum>
*
* Example filter list:
*
* Column(0) > 1 AND Column(0) < 5 AND Column(2) == "third"
*
* Yields the following serialized string:
*
* a0c23s1d1o2a1c23s1d5o1a2c25s5dthirdo5l0l0
*
* Where:
*
* a0	  - first column of table
* c23	  - scalar constant with type oid 23(INT4)
* s1	  - size of constant in bytes
* d1	  - serialized constant value
* o2	  - greater than operation
* a1	  - second column of table
* c23	  - scalar constant with type oid 23(INT4)
* s1	  - size of constant in bytes
* d5	  - serialized constant value
* o1	  - less than operation
* a2	  - third column of table
* c25	  - scalar constant with type oid 25(TEXT)
* s5	  - size of constant in bytes
* dthird - serialized constant value
* o5	  - equals operation
* l0	  - AND operator
* l0	  - AND operator
*
*/
static char *
PxfSerializeFilterList(List *filters)
{
	StringInfo	resbuf;
	ListCell   *lc = NULL;

	if (list_length(filters) == 0)
		return NULL;

	resbuf = makeStringInfo();

	/*
	 * Iterate through the expression items in the list and serialize them one
	 * after the other.
	 */
	foreach(lc, filters)
	{
		ExpressionItem *expressionItem = (ExpressionItem *) lfirst(lc);
		Node	   *node = expressionItem->node;
		NodeTag		tag = nodeTag(node);

		switch (tag)
		{
			case T_Var:
				{
					elog(DEBUG1,
						 "PxfSerializeFilterList: node tag %d (T_Var)",
						 tag);
					PxfFilterDesc
							   *filter = (PxfFilterDesc *) palloc0(sizeof(PxfFilterDesc));
					Var		   *var = (Var *) node;

					if (VarToPxfFilter(var, filter))
					{
						PxfOperand	l = filter->l;
						PxfOperand	r = filter->r;
						PxfOperatorCode o = filter->op;

						if (pxfoperand_is_attr(l) && pxfoperand_is_scalar_const(r))
						{
							appendStringInfo(resbuf,
											 "%c%d%c%d%c%lu%c%s",
											 PXF_ATTR_CODE,
											 l.attnum - 1,	/* Java attrs are
															 * 0-based */
											 PXF_SCALAR_CONST_CODE,
											 r.consttype,
											 PXF_SIZE_BYTES,
											 strlen(r.conststr->data),
											 PXF_CONST_DATA,
											 (r.conststr)->data);
						}
						else
						{
							/*
							 * VarToPxfFilter() should have never let this
							 * happen
							 */
							elog(ERROR,
								 "internal error in pxf_filter.c:PxfSerializeFilterList"
								 ". Found a non const+attr filter");
						}
						appendStringInfo(resbuf, "%c%d", PXF_OPERATOR_CODE, o);
						PxfFreeFilter(filter);

					}
					else
					{
						/*
						 * if at least one expression item is not supported,
						 * whole filter doesn't make sense
						 */
						elog(DEBUG1,
							 "Query will not be optimized to use filter push-down.");
						pfree(filter);
						pfree(resbuf->data);
						return NULL;
					}
					break;
				}
			case T_OpExpr:
				{
					elog(DEBUG1,
						 "PxfSerializeFilterList: node tag %d (T_OpExpr)",
						 tag);
					PxfFilterDesc
							   *filter =
					(PxfFilterDesc *) palloc0(sizeof(PxfFilterDesc));
					OpExpr	   *expr = (OpExpr *) node;

					if (OpExprToPxfFilter(expr, filter))
					{
						PxfOperand	l = filter->l;
						PxfOperand	r = filter->r;
						PxfOperatorCode o = filter->op;

						if (pxfoperand_is_attr(l) && pxfoperand_is_scalar_const(r))
						{
							appendStringInfo(resbuf,
											 "%c%d%c%d%c%lu%c%s",
											 PXF_ATTR_CODE,
											 l.attnum - 1,	/* Java attrs are
															 * 0-based */
											 PXF_SCALAR_CONST_CODE,
											 r.consttype,
											 PXF_SIZE_BYTES,
											 strlen(r.conststr->data),
											 PXF_CONST_DATA,
											 (r.conststr)->data);
						}
						else if (pxfoperand_is_scalar_const(l) &&
								 pxfoperand_is_attr(r))
						{
							appendStringInfo(resbuf,
											 "%c%d%c%lu%c%s%c%d",
											 PXF_SCALAR_CONST_CODE,
											 l.consttype,
											 PXF_SIZE_BYTES,
											 strlen(l.conststr->data),
											 PXF_CONST_DATA,
											 (l.conststr)->data,
											 PXF_ATTR_CODE,
											 r.attnum - 1); /* Java attrs are
															 * 0-based */
						}
						else if (pxfoperand_is_attr(l) && pxfoperand_is_list_const(r))
						{
							appendStringInfo(resbuf, "%c%d%c%d%s",
											 PXF_ATTR_CODE, l.attnum - 1,	/* Java attrs are
																			 * 0-based */
											 PXF_LIST_CONST_CODE, r.consttype,
											 r.conststr->data);
						}
						else if (pxfoperand_is_list_const(l) && pxfoperand_is_attr(r))
						{
							appendStringInfo(resbuf, "%c%d%s%c%d",
											 PXF_LIST_CONST_CODE, l.consttype,
											 l.conststr->data,
											 PXF_ATTR_CODE, r.attnum - 1);	/* Java attrs are
																			 * 0-based */
						}
						else
						{
							/*
							 * OpExprToPxfFilter() should have never let this
							 * happen
							 */
							elog(ERROR,
								 "internal error in pxf_filter.c:PxfSerializeFilterList"
								 ". Found a non const+attr filter");
						}
						/*
						 * For NOT LIKE case the negation is applied to PXFOP_LIKE.
						 */
						if (o == PXFOP_NOTLIKE)
						{
							appendStringInfo(resbuf, "%c%d", PXF_OPERATOR_CODE, PXFOP_LIKE);
							appendStringInfo(resbuf, "%c%d", PXF_LOGICAL_OPERATOR_CODE, NOT_EXPR);
						}
						else
							appendStringInfo(resbuf, "%c%d", PXF_OPERATOR_CODE, o);
						PxfFreeFilter(filter);
					}
					else
					{
						/*
						 * if at least one expression item is not supported,
						 * whole filter doesn't make sense
						 */
						elog(DEBUG1,
							 "Query will not be optimized to use filter push-down.");
						pfree(filter);
						pfree(resbuf->data);
						return NULL;
					}
					break;
				}
			case T_ScalarArrayOpExpr:
				{
					elog(DEBUG1,
						 "PxfSerializeFilterList: node tag %d (T_ScalarArrayOpExpr)",
						 tag);
					ScalarArrayOpExpr *expr = (ScalarArrayOpExpr *) node;
					PxfFilterDesc *filter =
					(PxfFilterDesc *) palloc0(sizeof(PxfFilterDesc));

					if (ScalarArrayOpExprToPxfFilter(expr, filter))
					{
						PxfOperand	l = filter->l;
						PxfOperand	r = filter->r;
						PxfOperatorCode o = filter->op;

						if (pxfoperand_is_attr(l) && pxfoperand_is_list_const(r))
						{
							appendStringInfo(resbuf, "%c%d%c%d%s",
											 PXF_ATTR_CODE, l.attnum - 1,	/* Java attrs are
																			 * 0-based */
											 PXF_LIST_CONST_CODE, r.consttype,
											 r.conststr->data);
						}
						else if (pxfoperand_is_list_const(l) &&
								 pxfoperand_is_attr(r))
						{
							appendStringInfo(resbuf, "%c%d%s%c%d",
											 PXF_LIST_CONST_CODE, l.consttype,
											 l.conststr->data,
											 PXF_ATTR_CODE, r.attnum - 1);	/* Java attrs are
																			 * 0-based */
						}
						else
						{
							/*
							 * scalararrayopexpr_to_pxffilter() should have
							 * never let this happen
							 */
							elog(ERROR,
								 "internal error in pxf_filter.c:PxfSerializeFilterList"
								 ". Found a non const+attr filter");
						}
						/*
						 * For NOT IN case the negation is applied to PXFOP_IN.
						 */
						if (o == PXFOP_NOTIN)
						{
							appendStringInfo(resbuf, "%c%d", PXF_OPERATOR_CODE, PXFOP_IN);
							appendStringInfo(resbuf, "%c%d", PXF_LOGICAL_OPERATOR_CODE, NOT_EXPR);
						}
						else
							appendStringInfo(resbuf, "%c%d", PXF_OPERATOR_CODE, o);
						PxfFreeFilter(filter);
					}
					else
					{
						/*
						 * if at least one expression item is not supported,
						 * whole filter doesn't make sense
						 */
						elog(DEBUG1,
							 "Query will not be optimized to use filter push-down.");
						pfree(filter);
						pfree(resbuf->data);
						return NULL;
					}
					break;
				}
			case T_BoolExpr:
				{
					BoolExpr   *expr = (BoolExpr *) node;
					BoolExprType boolType = expr->boolop;

					elog(DEBUG1,
						 "PxfSerializeFilterList: node tag %d (T_BoolExpr), bool node type %d",
						 tag,
						 boolType);
					appendStringInfo(resbuf,
									 "%c%d",
									 PXF_LOGICAL_OPERATOR_CODE,
									 boolType);
					break;
				}
			case T_NullTest:
				{
					elog(DEBUG1,
						 "PxfSerializeFilterList: node tag %d (T_NullTest)",
						 tag);
					NullTest   *expr = (NullTest *) node;
					Var		   *var = (Var *) expr->arg;

					/* TODO: add check for supported operation */
					if (!SupportedFilterType(var->vartype))
					{
						elog(DEBUG1,
							 "Query will not be optimized to use filter push-down.");
						return NULL;
					}

					/*
					 * filter expression for T_NullTest will not have any
					 * constant value
					 */
					if (expr->nulltesttype == IS_NULL)
					{
						appendStringInfo(resbuf,
										 "%c%d%c%d",
										 PXF_ATTR_CODE,
										 var->varattno - 1,
										 PXF_OPERATOR_CODE,
										 PXFOP_IS_NULL);
					}
					else if (expr->nulltesttype == IS_NOT_NULL)
					{
						appendStringInfo(resbuf,
										 "%c%d%c%d",
										 PXF_ATTR_CODE,
										 var->varattno - 1,
										 PXF_OPERATOR_CODE,
										 PXFOP_IS_NOTNULL);
					}
					else
					{
						elog(ERROR,
							 "internal error in pxf_filter.c:PxfSerializeFilterList.:"
							 " Found a NullTest filter with incorrect NullTestType");
					}
					break;
				}
			default:
				{
					elog(DEBUG5, "Skipping tag: %d", tag);
				}
		}
	}

	if (resbuf->len == 0)
	{
		pfree(resbuf->data);
		return NULL;
	}

	return resbuf->data;
}

/*
* OpExprToPxfFilter
*
* check if an OpExpr qualifies to be pushed-down to PXF.
* if it is - create it and return a success code.
*/
static bool
OpExprToPxfFilter(OpExpr *expr, PxfFilterDesc *filter)
{
	Node	   *leftop = NULL;
	Node	   *rightop = NULL;
	Oid			rightop_type = InvalidOid;
	Oid			leftop_type = InvalidOid;
	TypeCacheEntry *typentry = NULL;
	ArrayType	   *arr = NULL;

	if ((!expr) || (!filter))
		return false;

	leftop = get_leftop((Expr *) expr);
	rightop = get_rightop((Expr *) expr);
	leftop_type = exprType(leftop);
	rightop_type = exprType(rightop);

	/* only binary oprs supported currently */
	if (!rightop)
	{
		elog(DEBUG1, "OpExprToPxfFilter: unary op! leftop_type: %d, op: %d",
			 leftop_type, expr->opno);
		return false;
	}

	elog(DEBUG1, "OpExprToPxfFilter: leftop (expr type: %d, arg type: %d), "
		 "rightop_type (expr type: %d, arg type %d), op: %d",
		 leftop_type, nodeTag(leftop),
		 rightop_type, nodeTag(rightop),
		 expr->opno);

	if (IsA(leftop, RelabelType))
	{
		/*
		 * Checks if the arg is of type Var, and if it is uses the Var as the left operator
		 */
		RelabelType *relabelType = (RelabelType *) leftop;
		Expr *exprNode = relabelType->arg;

		if (IsA(exprNode, Var))
		{
			leftop = (Node *)exprNode;
		}
	}

	if (IsA(rightop, RelabelType))
	{
		/*
		 * Checks if the arg is of type Var, and if it is uses the Var as the right operator
		 */
		RelabelType *relabelType = (RelabelType *) rightop;
		Expr *exprNode = relabelType->arg;

		if (IsA(exprNode, Var))
		{
			rightop = (Node *)exprNode;
		}
	}

	/*
	 * check if GPDB supports scalar operators for the arrays elements before
	 * sending the read request. Otherwise GPDB will reject the query during
	 * ExecQual execution anyway after data is returned from pxf, because the
	 * filter operator is not supported (for arrays comparison this validation
	 * is not held during parsing stage and we have to prevent unnecessary
	 * manipulations). Checking the existence of equality operator for array
	 * element type is enough to validate other scalar operators as well.
	 */
	if (IsA(leftop, Var) && IsA(rightop, Const) &&
		SupportedArrayType(rightop_type))
	{
		Const	*right = (Const *) rightop;

		if (right->constisnull)
			return false;

		arr = DatumGetArrayTypeP(right->constvalue);
	}
	else if (IsA(leftop, Const) && IsA(rightop, Var) &&
			SupportedArrayType(leftop_type))
	{
		Const	*left = (Const *) leftop;

		if (left->constisnull)
			return false;

		arr = DatumGetArrayTypeP(left->constvalue);
	}

	if (arr != NULL)
	{
		typentry = lookup_type_cache(ARR_ELEMTYPE(arr),
									 TYPECACHE_EQ_OPR_FINFO);

		if (!OidIsValid(typentry->eq_opr_finfo.fn_oid))
			ereport(ERROR,
					(errcode(ERRCODE_UNDEFINED_FUNCTION),
			errmsg("could not identify a comparison operator for type %s",
				   format_type_be(ARR_ELEMTYPE(arr)))));
	}

	/*
	 * check if supported type -
	 */
	if (!SupportedFilterType(rightop_type) || !SupportedFilterType(leftop_type))
		return false;

	/*
	 * check if supported operator -
	 */
	if (!SupportedOperatorTypeOpExpr(expr->opno, filter))
		return false;

	/* arguments must be VAR and CONST */
	if (IsA(leftop, Var) && IsA(rightop, Const))
	{
		filter->l.opcode = PXF_ATTR_CODE;
		filter->l.attnum = ((Var *) leftop)->varattno;
		filter->l.consttype = InvalidOid;
		if (filter->l.attnum <= InvalidAttrNumber)
			return false;		/* system attr not supported */

		filter->r.attnum = InvalidAttrNumber;
		filter->r.conststr = makeStringInfo();
		filter->r.consttype = ((Const *) rightop)->consttype;

		/*
		 * If we faced the generic array comparison operator, represent
		 * the array through list const.
		 */
		if (SupportedArrayType(rightop_type))
		{
			filter->r.opcode = PXF_LIST_CONST_CODE;
			ListConstToStr((Const *) rightop, filter->r.conststr, true);
		}
		else
		{
			filter->r.opcode = PXF_SCALAR_CONST_CODE;
			ScalarConstToStr((Const *) rightop, filter->r.conststr);
		}
	}
	else if (IsA(leftop, Const) && IsA(rightop, Var))
	{
		filter->l.attnum = InvalidAttrNumber;
		filter->l.conststr = makeStringInfo();
		filter->l.consttype = ((Const *) leftop)->consttype;
		if (SupportedArrayType(leftop_type))
		{
			filter->l.opcode = PXF_LIST_CONST_CODE;
			ListConstToStr((Const *) leftop, filter->l.conststr, true);
		}
		else
		{
			filter->l.opcode = PXF_SCALAR_CONST_CODE;
			ScalarConstToStr((Const *) leftop, filter->l.conststr);
		}

		filter->r.opcode = PXF_ATTR_CODE;
		filter->r.attnum = ((Var *) rightop)->varattno;
		filter->r.consttype = InvalidOid;
		if (filter->r.attnum <= InvalidAttrNumber)
			return false;		/* system attr not supported */
	}
	else
	{
		elog(DEBUG1, "OpExprToPxfFilter: expression is not a Var+Const");
		return false;
	}

	return true;
}

static bool
ScalarArrayOpExprToPxfFilter(ScalarArrayOpExpr *expr, PxfFilterDesc *filter)
{

	Node	   *leftop = NULL;
	Node	   *rightop = NULL;

	leftop = (Node *) linitial(expr->args);
	rightop = (Node *) lsecond(expr->args);
	Oid			leftop_type = exprType(leftop);
	Oid			rightop_type = exprType(rightop);

	/*
	 * check if supported type -
	 */
	if (!SupportedFilterType(rightop_type) || !SupportedFilterType(leftop_type))
		return false;

	/*
	 * check if supported operator -
	 */
	if (!SupportedOperatorTypeScalarArrayOpExpr(expr->opno,
												filter,
												expr->useOr))
		return false;

	if (IsA(leftop, RelabelType))
	{
		/*
		 * Checks if the arg is of type Var, and if it is uses the Var as the left operator
		 */
		RelabelType *relabelType = (RelabelType *) leftop;
		Expr *exprNode = relabelType->arg;

		if (IsA(exprNode, Var))
		{
			leftop = (Node *)exprNode;
		}
	}

	if (IsA(rightop, RelabelType))
	{
		/*
		 * Checks if the arg is of type Var, and if it is uses the Var as the right operator
		 */
		RelabelType *relabelType = (RelabelType *) rightop;
		Expr *exprNode = relabelType->arg;

		if (IsA(exprNode, Var))
		{
			rightop = (Node *)exprNode;
		}
	}

	if (IsA(leftop, Var) &&IsA(rightop, Const))
	{
		filter->l.opcode = PXF_ATTR_CODE;
		filter->l.attnum = ((Var *) leftop)->varattno;
		filter->l.consttype = InvalidOid;
		if (filter->l.attnum <= InvalidAttrNumber)
			return false;		/* system attr not supported */

		filter->r.opcode = PXF_LIST_CONST_CODE;
		filter->r.attnum = InvalidAttrNumber;
		filter->r.conststr = makeStringInfo();
		ListConstToStr((Const *) rightop, filter->r.conststr, false);
		filter->r.consttype = ((Const *) rightop)->consttype;
	}
	else if (IsA(leftop, Const) &&IsA(rightop, Var))
	{
		filter->l.opcode = PXF_LIST_CONST_CODE;
		filter->l.attnum = InvalidAttrNumber;
		filter->l.conststr = makeStringInfo();
		ListConstToStr((Const *) leftop, filter->l.conststr, false);
		filter->l.consttype = ((Const *) leftop)->consttype;

		filter->r.opcode = PXF_ATTR_CODE;
		filter->r.attnum = ((Var *) rightop)->varattno;
		filter->r.consttype = InvalidOid;
		if (filter->r.attnum <= InvalidAttrNumber)
			return false;		/* system attr not supported */
	}
	else
	{
		elog(DEBUG1,
			 "PxfSerializeFilterList: expression is not a Var+Const");
		return false;
	}

	return true;
}

static bool
VarToPxfFilter(Var *var, PxfFilterDesc *filter)
{
	Oid			var_type = InvalidOid;

	if ((!var) || (!filter))
		return false;

	var_type = exprType((Node *) var);

	/*
	 * check if supported type -
	 */
	if (!SupportedFilterType(var_type))
		return false;

	/* arguments must be VAR and CONST */
	if (IsA(var, Var))
	{
		filter->l.opcode = PXF_ATTR_CODE;
		filter->l.attnum = var->varattno;
		filter->l.consttype = InvalidOid;
		if (filter->l.attnum <= InvalidAttrNumber)
			return false;		/* system attr not supported */

		filter->r.opcode = PXF_SCALAR_CONST_CODE;
		filter->r.attnum = InvalidAttrNumber;
		filter->r.conststr = makeStringInfo();
		appendStringInfo(filter->r.conststr, TrueConstValue);
		filter->r.consttype = BOOLOID;
	}
	else
	{
		elog(DEBUG1, "VarToPxfFilter: expression is not a Var");
		return false;
	}

	return true;
}

static List *
AppendAttrFromVar(Var *var, List *attrs)
{
	AttrNumber	varattno = var->varattno;

	/* system attr not supported */
	if (varattno > InvalidAttrNumber)
		return lappend_int(attrs, varattno - 1);

	return attrs;
}

/*
* append_attr_from_func_args
*
* extracts all columns from FuncExpr into attrs
* assigns false to expressionIsSupported if at least one of items is not supported
*/
static List *
append_attr_from_func_args(FuncExpr *expr,
						   List *attrs,
						   bool *expressionIsSupported)
{
	if (!expressionIsSupported)
	{
		return NIL;
	}
	ListCell   *lc = NULL;

	foreach(lc, expr->args)
	{
		Node	   *node = (Node *) lfirst(lc);

		if (IsA(node, FuncExpr))
		{
			attrs = append_attr_from_func_args((FuncExpr *) node,
											   attrs,
											   expressionIsSupported);
		}
		else if (IsA(node, Var))
		{
			attrs = AppendAttrFromVar((Var *) node, attrs);
		}
		else if (IsA(node, OpExpr))
		{
			attrs = GetAttrsFromExpr((Expr *) node, expressionIsSupported);
		}
		else
		{
			*expressionIsSupported = false;
			return NIL;
		}
	}

	return attrs;

}

/*
* GetAttrsFromExpr
*
* extracts and returns list of all columns from Expr
* assigns false to expressionIsSupported if at least one of items is not supported
*/
static List *
GetAttrsFromExpr(Expr *expr, bool *expressionIsSupported)
{
	Node	   *leftop = NULL;
	Node	   *rightop = NULL;
	List	   *attrs = NIL;

	*expressionIsSupported = true;

	if ((!expr))
		return attrs;

	if (IsA(expr, OpExpr))
	{
		leftop = get_leftop(expr);
		rightop = get_rightop(expr);
	}
	else if (IsA(expr, ScalarArrayOpExpr))
	{
		ScalarArrayOpExpr *saop = (ScalarArrayOpExpr *) expr;

		leftop = (Node *) linitial(saop->args);
		rightop = (Node *) lsecond(saop->args);
	}
	else
	{
		/* If expression type is not known, report that it's not supported */
		*expressionIsSupported = false;
		return NIL;
	}

	/* We support following combinations of operands: */
	/* Var, Const */
	/* Relabel, Const */
	/* FuncExpr, Const */
	/* Const, Var */
	/* Const, Relabel */
	/* Const, FuncExpr */
	/* For most of datatypes column is represented by Var node */
	/* For varchar column is represented by RelabelType node */
	if (IsA(leftop, Var) &&IsA(rightop, Const))
	{
		attrs = AppendAttrFromVar((Var *) leftop, attrs);
	}
	else if (IsA(leftop, RelabelType) &&IsA(rightop, Const))
	{
		attrs = AppendAttrFromVar((Var *) ((RelabelType *) leftop)->arg, attrs);
	}
	else if (IsA(leftop, FuncExpr) &&IsA(rightop, Const))
	{
		FuncExpr   *expr = (FuncExpr *) leftop;

		attrs = append_attr_from_func_args(expr, attrs, expressionIsSupported);
	}
	else if (IsA(rightop, Var) &&IsA(leftop, Const))
	{
		attrs = AppendAttrFromVar((Var *) rightop, attrs);
	}
	else if (IsA(rightop, RelabelType) &&IsA(leftop, Const))
	{
		attrs =
			AppendAttrFromVar((Var *) ((RelabelType *) rightop)->arg, attrs);
	}
	else if (IsA(rightop, FuncExpr) &&IsA(leftop, Const))
	{
		FuncExpr   *expr = (FuncExpr *) rightop;

		attrs = append_attr_from_func_args(expr, attrs, expressionIsSupported);
	}
	else
	{
		/*
		 * If operand type or combination is not known, report that it's not
		 * supported
		 */
		/* to avoid partially extracted attributes from expression */
		*expressionIsSupported = false;
		return NIL;
	}

	return attrs;

}

/*
* SupportedFilterType
*
* Return true if the type is supported by pxf_filter.
* Supported defines are defined in pxf_supported_types.
*/
static bool
SupportedFilterType(Oid type)
{
	int			nargs = sizeof(pxf_supported_types) / sizeof(Oid);
	int			i;

	/* is type supported? */
	for (i = 0; i < nargs; i++)
	{
		if (type == pxf_supported_types[i])
			return true;
	}

	elog(DEBUG1,
		 "SupportedFilterType: filter pushdown is not supported for datatype oid: %d",
		 type);

	return false;
}

/*
* SupportedArrayType
*
* Return true if the array type is supported by pxf_filter.
* Supported defines are defined in pxf_supported_array_types.
*/
static bool
SupportedArrayType(Oid type)
{
	int			nargs = sizeof(pxf_supported_array_types) / sizeof(Oid);
	int			i;

	/* is type supported? */
	for (i = 0; i < nargs; i++)
	{
		if (type == pxf_supported_array_types[i])
			return true;
	}

	return false;
}

static bool
SupportedOperatorTypeOpExpr(Oid type, PxfFilterDesc *filter)
{

	int			nargs = sizeof(pxf_supported_opr_op_expr) / sizeof(dbop_pxfop_map);
	int			i;

	/* is operator supported? if so, set the corresponding PXFOP */
	for (i = 0; i < nargs; i++)
	{
		/* NOTE: switch to hash table lookup if   */
		/* array grows. for now it's cheap enough */
		if (type == pxf_supported_opr_op_expr[i].dbop)
		{
			filter->op = pxf_supported_opr_op_expr[i].pxfop;
			return true;		/* filter qualifies! */
		}
	}

	elog(DEBUG1,
		 "OpExprToPxfFilter: operator is not supported, operator code: %d",
		 type);

	return false;
}

static bool
SupportedOperatorTypeScalarArrayOpExpr(Oid type,
									   PxfFilterDesc *filter,
									   bool useOr)
{

	int			nargs = sizeof(pxf_supported_opr_scalar_array_op_expr) /
	sizeof(dbop_pxfop_array_map);
	int			i;

	/* is operator supported? if so, set the corresponding PXFOP */
	for (i = 0; i < nargs; i++)
	{
		/* NOTE: switch to hash table lookup if   */
		/* array grows. for now it's cheap enough */
		if (useOr == pxf_supported_opr_scalar_array_op_expr[i].useOr &&
			type == pxf_supported_opr_scalar_array_op_expr[i].dbop)
		{
			filter->op = pxf_supported_opr_scalar_array_op_expr[i].pxfop;
			return true;		/* filter qualifies! */
		}
	}

	elog(DEBUG1,
		 "SupportedOperatorTypeScalarArrayOpExpr: operator is not supported, operator code: %d",
		 type);

	return false;
}

/*
* const_to_str
*
* Extract the value stored in a const operand into a string. If the operand
* type is text based, make sure to escape the value with surrounding quotes.
*/
static void
ScalarConstToStr(Const *constval, StringInfo buf)
{
	Oid			typoutput;
	bool		typIsVarlena;
	char	   *extval;

	if (constval->constisnull)
	{
		/* TODO: test this edge case and its consequences */
		appendStringInfo(buf, NullConstValue);
		return;
	}

	getTypeOutputInfo(constval->consttype,
					  &typoutput, &typIsVarlena);

	extval = OidOutputFunctionCall(typoutput, constval->constvalue);

	switch (constval->consttype)
	{
		case INT2OID:
		case INT4OID:
		case INT8OID:
		case FLOAT4OID:
		case FLOAT8OID:
		case NUMERICOID:
		case TEXTOID:
		case VARCHAROID:
		case BPCHAROID:
		case CHAROID:
		case BYTEAOID:
		case DATEOID:
		case TIMESTAMPOID:
		case TIMEOID:
		case TIMESTAMPTZOID:
		case INTERVALOID:
		case UUIDOID:
		case JSONBOID:
			appendStringInfo(buf, "%s", extval);
			break;
		case BOOLOID:
			if (*extval == 't')
				appendStringInfo(buf, "%s", TrueConstValue);
			else
				appendStringInfo(buf, "%s", FalseConstValue);
			break;
		default:
			/* should never happen. we filter on types earlier */
			elog(ERROR,
				 "internal error in pxf_filter.c:ScalarConstToStr. "
				 "Using unsupported data type (%d) (value %s)",
				 constval->consttype, extval);

	}
}

/*
* ListConstToStr
*
* Extracts the value stored in a list constant to a string.
* Example:
* Input: ['abc', 'xyz']
* Output: s3dabcs3dxyz
*
*/
static void
ListConstToStr(Const *constval, StringInfo buf, bool with_nulls)
{
	if (constval->constisnull)
	{
		elog(DEBUG1, "Null constant is not expected in this context.");
		return;
	}

	if (constval->constbyval)
	{
		elog(DEBUG1,
			 "Constant passed by value is not expected in this context.");
		return;
	}

	switch (constval->consttype)
	{
		case INT2ARRAYOID:
		case INT4ARRAYOID:
		case INT8ARRAYOID:
		case TEXTARRAYOID:
		case BOOLARRAYOID:
		case BYTEAARRAYOID:
		case FLOAT4ARRAYOID:
		case FLOAT8ARRAYOID:
		case BPCHARARRAYOID:
		case VARCHARARRAYOID:
		case DATEARRAYOID:
		case TIMEARRAYOID:
		case TIMESTAMPARRAYOID:
		case TIMESTAMPTZARRAYOID:
		case INTERVALARRAYOID:
		case NUMERICARRAYOID:
		case UUIDARRAYOID:
		case JSONBARRAYOID:
			{
				StringInfo	interm_buf;
				Datum	   *dats;
				ArrayType  *arr;
				int			len;
				Oid			typoutput;
				bool		typIsVarlena;
				int16		elmlen;
				bool		elmbyval;
				char		elmalign;
				bool	   *elem_nulls = NULL;

				arr = DatumGetArrayTypeP(constval->constvalue);

				interm_buf = makeStringInfo();
				/*
				 * Get necessary data for deconstruct_array() and output function
				 * from the catalog.
				 */
				get_typlenbyvalalign(ARR_ELEMTYPE(arr), &elmlen, &elmbyval, &elmalign);
				deconstruct_array(arr,
								  ARR_ELEMTYPE(arr),
								  elmlen,
								  elmbyval,
								  elmalign,
								  &dats,
								  with_nulls ? &elem_nulls : NULL,
								  &len);

				getTypeOutputInfo(ARR_ELEMTYPE(arr), &typoutput, &typIsVarlena);

				for (int i = 0; i < len; i++)
				{
					char *extval = NullConstValue;

					if (!with_nulls || !elem_nulls[i])
						extval = OidOutputFunctionCall(typoutput, dats[i]);

					if (ARR_ELEMTYPE(arr) != BOOLOID)
						appendStringInfo(interm_buf, "%s", extval);
					else
					{
						if (*extval == 't')
							appendStringInfo(interm_buf, "%s", TrueConstValue);
						else if (*extval == 'f')
							appendStringInfo(interm_buf, "%s", FalseConstValue);
						else
							appendStringInfo(interm_buf, "%s", NullConstValue);
					}

					appendStringInfo(buf, "%c%d%c%s",
									 PXF_SIZE_BYTES, interm_buf->len,
									 PXF_CONST_DATA, interm_buf->data);
					resetStringInfo(interm_buf);

					if (!with_nulls || !elem_nulls[i])
						pfree(extval);
				}
				pfree(interm_buf->data);
				break;
			}
		default:
			/* should never happen. we filter on types earlier */
			elog(ERROR,
				 "internal error in pxf_filter.c:ListConstToStr. "
				 "Using unsupported data type (%d)",
				 constval->consttype);

	}
}

/*
* SerializePxfFilterQuals
*
* Wrapper around pxf_make_filter_list -> PxfSerializeFilterList.
*
* The function accepts the scan qual list and produces a serialized
* string that represents the push down filters (See called functions
* headers for more information).
*/
char *
SerializePxfFilterQuals(List *quals)
{
	char	   *result = NULL;

	if (quals == NULL)
		return result;

	List	   *clauses = NULL;
	ListCell   *lc;

	foreach(lc, quals)
	{
		RestrictInfo *ri = (RestrictInfo *) lfirst(lc);

		clauses = lappend(clauses, ri->clause);
	}

	/*
	 * expressionItems will contain all the expressions including comparator
	 * and logical operators in postfix order
	 */
	List	   *expressionItems = PxfMakeExpressionItemsList(clauses, NULL);

	/*
	 * result will contain serialized version of the above postfix ordered
	 * expressions list
	 */
	result = PxfSerializeFilterList(expressionItems);

	PxfFreeExpressionItemsList(expressionItems);

	elog(DEBUG1,
		 "serializePxfFilterQuals: resulting filter string is '%s'",
		 (result == NULL) ? "" : result);

	return result;
}

/*
* Adds a given number of AND expression items to an existing list of expression items
*/
void
AddExtraAndExpressionItems(List *expressionItems, int extraAndOperatorsNum)
{
	if ((!expressionItems) || (extraAndOperatorsNum < 1))
	{
		return;
	}

	ExpressionItem
			   *andExpressionItem = (ExpressionItem *) palloc0(sizeof(ExpressionItem));

	BoolExpr   *andExpr = makeNode(BoolExpr);

	andExpr->boolop = AND_EXPR;
	andExpressionItem->node = (Node *) andExpr;
	andExpressionItem->parent = NULL;
	andExpressionItem->processed = false;

	for (int i = 0; i < extraAndOperatorsNum; i++)
	{
		expressionItems = lappend(expressionItems, andExpressionItem);
	}
}

/*
* Returns a list of attributes, extracted from quals.
* Returns NIL if any error occurred.
* Supports AND, OR, NOT operations.
* Supports =, <, <=, >, >=, IS NULL, IS NOT NULL, BETWEEN, IN operators.
* List might contain duplicates.
* Caller should release memory once result is not needed.
*/
List *
extractPxfAttributes(List *quals, bool *qualsAreSupported)
{
	if (!(*qualsAreSupported))
		return NIL;
	ListCell   *lc = NULL;
	List	   *attributes = NIL;
	bool		expressionIsSupported;

	*qualsAreSupported = true;

	if (list_length(quals) == 0)
		return NIL;

	foreach(lc, quals)
	{
		Node	   *node = (Node *) lfirst(lc);
		NodeTag		tag = nodeTag(node);

		switch (tag)
		{
			case T_OpExpr:
			case T_ScalarArrayOpExpr:
				{
					Expr	   *expr = (Expr *) node;
					List	   *attrs = GetAttrsFromExpr(expr,
														 &expressionIsSupported);

					if (!expressionIsSupported)
					{
						*qualsAreSupported = false;
						return NIL;
					}
					attributes = list_concat(attributes, attrs);
					break;
				}
			case T_BoolExpr:
				{
					BoolExpr   *expr = (BoolExpr *) node;
					bool		innerBoolQualsAreSupported = true;
					List	   *inner_result =
					extractPxfAttributes(expr->args,
										 &innerBoolQualsAreSupported);

					if (!innerBoolQualsAreSupported)
					{
						*qualsAreSupported = false;
						return NIL;
					}
					attributes = list_concat(attributes, inner_result);
					break;
				}
			case T_NullTest:
				{
					NullTest   *expr = (NullTest *) node;

					attributes =
						AppendAttrFromVar((Var *) expr->arg, attributes);
					break;
				}
			case T_BooleanTest:
				{
					BooleanTest *expr = (BooleanTest *) node;

					attributes =
						AppendAttrFromVar((Var *) expr->arg, attributes);
					break;
				}
			default:
				{
					/*
					 * tag is not supported, it's risk of having: 1)
					 * false-positive tuples 2) unable to join tables 3) etc
					 */
					elog(INFO,
						 "extractPxfAttributes: unsupported node tag %d, unable to extract attribute from qualifier",
						 tag);
					return NIL;
				}
		}
	}

	return attributes;
}
