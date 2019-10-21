/*
 * parser/sql_test.h
 * Copyright (C) 2019 chenjing <chenjing@4paradigm.com>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include "parser/node.h"
#include "gtest/gtest.h"
#include <strstream>

namespace fedb {
namespace sql {

/**
 * TODO: add unit test for MakeXXXXXNode
 * add unit test and check attributions
 */
class SqlNodeTest : public ::testing::Test {

public:
    SqlNodeTest() {}

    ~SqlNodeTest() {}
};

TEST_F(SqlNodeTest, MakeNode) {
    using namespace std;
    SQLNode *node = fedb::sql::MakeNode(kAll);
    cout << *node << endl;
    std::strstream out;
    out << *node;
    ASSERT_STREQ("+kAll",
                 out.str());
}

TEST_F(SqlNodeTest, MakeColumnRefNodeTest) {

    SQLNode *node = MakeColumnRefNode("col", "t");
    ColumnRefNode *columnnode = (ColumnRefNode *) node;

    ASSERT_EQ(kColumn, columnnode->GetType());
    ASSERT_EQ("t", columnnode->GetRelationName());
    ASSERT_EQ("col", columnnode->GetColumnName());

    std::strstream out;
    out << *node;
    ASSERT_STREQ("+kColumn\n"
                     "+\tcolumn_ref: {relation_name: t, column_name: col}",
                 out.str());
    std::cout << out.str() << std::endl;
}

TEST_F(SqlNodeTest, MakeConstNodeStringTest) {

    ConstNode *pNode = new ConstNode("sql string test");
    ASSERT_EQ(kString, pNode->GetType());
    std::strstream out;
    out << *pNode;
    std::cout << out.str() << std::endl;
    ASSERT_STREQ("+kString\n"
                     "+\tvalue: sql string test",
                 out.str());
}

TEST_F(SqlNodeTest, MakeConstNodeIntTest) {
    ConstNode *pNode = new ConstNode(1);
    ASSERT_EQ(kInt, pNode->GetType());
    std::strstream out;
    out << *pNode;
    std::cout << out.str() << std::endl;
    ASSERT_STREQ("+kInt\n"
                     "+\tvalue: 1",
                 out.str());
}

TEST_F(SqlNodeTest, MakeConstNodeFloatTest) {
    ConstNode *pNode = new ConstNode(1.234f);
    ASSERT_EQ(kFloat, pNode->GetType());
    std::strstream out;
    out << *pNode;
    std::cout << out.str() << std::endl;
    ASSERT_STREQ("+kFloat\n"
                     "+\tvalue: 1.234",
                 out.str());
}

TEST_F(SqlNodeTest, MakeWindowDefNodetTest) {
    SQLNodeList *partitions = NULL;
    SQLNodeList *orders = NULL;
    SQLNode *frame= NULL;
    SQLNode *node_ptr = MakeWindowDefNode(partitions, orders, frame);
    ASSERT_EQ(kWindowDef, node_ptr->GetType());
    std::strstream out;
    out << *node_ptr;
    std::cout << out.str() << std::endl;
    ASSERT_STREQ("+kWindowDef\n"
                     "+\twindow_name: \n"
                     "+\tpartition_list_ptr_: NULL\n"
                     "+\torder_list_ptr_: NULL\n"
                     "+\tframe_ptr: NULL",
    out.str());
}

TEST_F(SqlNodeTest, NewFrameNodeTest) {
    FrameBound *start = new FrameBound(kPreceding, NULL);
    FrameBound *end = new FrameBound(kPreceding, new ConstNode(86400000L));
    SQLNode *node_ptr = MakeFrameNode(start, end);
    MakeRangeFrameNode(node_ptr);

    ASSERT_EQ(kFrames, node_ptr->GetType());
    std::strstream out;
    out << *node_ptr;
    std::cout << out.str() << std::endl;
    ASSERT_STREQ("+kFrame\n"
                     "+\tframes_type_ : kFrameRange\n"
                     "+\tstart: \n"
                     "+\t\tkBound\n"
                     "+\t\t\tbound: kPreceding\n"
                     "+\t\t\t\tUNBOUNDED\n"
                     "+\tend: \n"
                     "+\t\tkBound\n"
                     "+\t\t\tbound: kPreceding\n"
                     "+\t\t\t\tkBigInt\n"
                     "+\t\t\t\t\tvalue: 86400000",
    out.str());
}



} // namespace of base
} // namespace of fedb

int main(int argc, char **argv) {
    ::testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}



