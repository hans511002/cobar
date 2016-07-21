package com.alibaba.cobar.parser.ast.fragment.ddl.partition;

import static com.alibaba.cobar.parser.recognizer.mysql.MySQLToken.IDENTIFIER;
import static com.alibaba.cobar.parser.recognizer.mysql.MySQLToken.OP_EQUALS;

import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.cobar.parser.ast.ASTNode;
import com.alibaba.cobar.parser.ast.expression.Expression;
import com.alibaba.cobar.parser.ast.expression.primary.Identifier;
import com.alibaba.cobar.parser.ast.expression.primary.literal.LiteralString;
import com.alibaba.cobar.parser.recognizer.mysql.MySQLToken;
import com.alibaba.cobar.parser.recognizer.mysql.lexer.MySQLLexer;
import com.alibaba.cobar.parser.recognizer.mysql.syntax.MySQLDDLParser;
import com.alibaba.cobar.parser.recognizer.mysql.syntax.MySQLDDLParser.SpecialIdentifier;
import com.alibaba.cobar.parser.visitor.SQLASTVisitor;

public class PartitionOption implements ASTNode {
	public static enum PartitionType {
		RANGE, LIST, HASH, KEY,
	}

	// static HashMap<Identifier, PartitionType> parTypeMap = new HashMap<Identifier, PartitionType>(11);
	// static {
	// parTypeMap.put(new Identifier(null, "range", "RANGE"), PartitionType.RANGE);
	// parTypeMap.put(new Identifier(null, "list", "LIST"), PartitionType.LIST);
	// parTypeMap.put(new Identifier(null, "hash", "HASH"), PartitionType.HASH);
	// parTypeMap.put(new Identifier(null, "key", "KEY"), PartitionType.KEY);
	// }

	public static class Partition {
		Identifier parName = null;
		Expression parValue = null;
		Identifier engine = null;

		LiteralString dataDir = null;
		LiteralString indexDir = null;
		List<Partition> subLists = null;

		public String toString(PartitionOption po, boolean isPar) {
			StringBuffer sb = new StringBuffer();
			sb.append(isPar ? "PARTITION " : "SUBPARTITION ");
			sb.append(parName.getIdText());
			if (isPar) {
				if (po.partitionType == PartitionType.RANGE) {
					sb.append(" VALUES LESS THAN(");
					sb.append(parValue.toString());
					sb.append(")");
				} else if (po.partitionType == PartitionType.LIST) {
					sb.append(" VALUES IN(");
					sb.append(parValue.toString());
					sb.append(")");
				}
				if (dataDir != null) {
					sb.append(" DATA DIRECTORY '");
					sb.append(dataDir.toString());
					sb.append("'");
				}
				if (indexDir != null) {
					sb.append(" INDEX DIRECTORY '");
					sb.append(indexDir.toString());
					sb.append("'");
				}
				if (engine != null) {
					sb.append(" ENGINE = " + engine + " ");
				}
				if (subLists != null) {
					sb.append("(");
					for (int i = 0; i < subLists.size(); i++) {
						if (i > 0) {
							sb.append(",");
						}
						sb.append(subLists.get(i).toString(po, false));
					}
					sb.append(")");
				}
			} else {
				if (po.subPartitionType == PartitionType.RANGE) {
					sb.append("VALUES LESS THAN(");
					sb.append(parValue.toString());
					sb.append(")");
				} else if (po.subPartitionType == PartitionType.LIST) {
					sb.append("VALUES IN(");
					sb.append(parValue.toString());
					sb.append(")");
				}
				if (dataDir != null) {
					sb.append(" DATA DIRECTORY '");
					sb.append(dataDir.toString());
					sb.append("'");
				}
				if (indexDir != null) {
					sb.append(" INDEX DIRECTORY '");
					sb.append(indexDir.toString());
					sb.append("'");
				}
			}
			return sb.toString();
		}
	}

	PartitionType partitionType = null;
	boolean haveColumnsKey = false;
	List<Identifier> partitionColumn = null;
	Identifier subPartitionColumn = null;
	PartitionType subPartitionType = null;
	boolean subHaveColumnsKey = false;
	List<Partition> partitionLists = null;
	int partitionSize = 0;

	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append("PARTITION BY ");
		sb.append(partitionType);
		if (haveColumnsKey)
			sb.append(" COLUMNS");
		sb.append("(");
		for (int i = 0; i < partitionColumn.size(); i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append(partitionColumn.get(i).getIdText());
		}
		sb.append(")");
		if (subPartitionType != null) {
			sb.append(" SUBPARTITION BY ");
			sb.append(subPartitionType);
			if (subHaveColumnsKey)
				sb.append(" COLUMNS");
			sb.append("(");
			sb.append(subPartitionColumn.getIdText());
			sb.append(")");
			if (partitionSize > 0) {
				sb.append("partitions " + partitionSize);
			}
		} else {
			if (partitionSize > 0) {
				sb.append("partitions " + partitionSize);
			}
		}
		if (partitionLists != null && partitionLists.size() > 0) {
			sb.append("(");
			for (int i = 0; i < partitionLists.size(); i++) {
				if (i > 0) {
					sb.append(",");
				}
				sb.append(partitionLists.get(i).toString(this, true));
			}
			sb.append(")");
		}
		return sb.toString();
	}

	public PartitionOption(MySQLDDLParser mySQLDDLParser, MySQLLexer lexer) throws SQLSyntaxErrorException {
		this.parseParitionSql(mySQLDDLParser, lexer);
		System.out.println(this.toString());
	}

	void parseParitionSql(MySQLDDLParser mySQLDDLParser, MySQLLexer lexer) throws SQLSyntaxErrorException {
		lexer.nextToken();
		if (lexer.token() != MySQLToken.KW_BY) {
			throw new SQLSyntaxErrorException("table partition Missing by keyword ");
		}
		lexer.nextToken();
		MySQLToken tk = lexer.token();
		if (tk == MySQLToken.KW_LINEAR || tk == MySQLToken.KW_COLUMNS) {
			if (tk == MySQLToken.KW_COLUMNS)
				haveColumnsKey = true;
			lexer.nextToken();
		}
		tk = lexer.token();
		switch (tk) {
		case KW_RANGE:
			partitionType = PartitionType.RANGE;
			break;
		case KW_LIST:
			partitionType = PartitionType.LIST;
			break;
		case KW_HASH:
			partitionType = PartitionType.HASH;
			break;
		case KW_KEY:
			partitionType = PartitionType.KEY;
			break;
		default:
			throw new SQLSyntaxErrorException("table partition type error[range,list,hash,key] "
					+ lexer.stringValueUppercase());
		}
		lexer.nextToken();
		if (lexer.token() != MySQLToken.PUNC_LEFT_PAREN) {
			throw new SQLSyntaxErrorException("table partition  column error Missing parenthesis");
		}
		lexer.nextToken();
		partitionColumn = new ArrayList<Identifier>();
		while (lexer.token() == MySQLToken.IDENTIFIER) {
			Identifier id = mySQLDDLParser.identifier();
			partitionColumn.add(id);
			if (lexer.token() == MySQLToken.PUNC_DOT || lexer.token() == MySQLToken.PUNC_COMMA) {
				lexer.nextToken();
			}
		}
		if (partitionColumn.size() == 0) {
			throw new SQLSyntaxErrorException("table partition column is null ");
		}
		if (lexer.token() != MySQLToken.PUNC_RIGHT_PAREN) {
			throw new SQLSyntaxErrorException("table partition type column error ");
		}
		lexer.nextToken();
		tk = lexer.token();
		if (partitionType == PartitionType.HASH || partitionType == PartitionType.KEY) {
			if (lexer.token() == MySQLToken.KW_PARTITIONS) {// PARTITIONS 4;
				lexer.nextToken();
				if (lexer.token() != MySQLToken.LITERAL_NUM_PURE_DIGIT)
					throw new SQLSyntaxErrorException("table partition size is not int ");
				partitionSize = lexer.integerValue().intValue();
				lexer.nextToken();
				return;
			}
		}

		if (tk != MySQLToken.PUNC_LEFT_PAREN && tk != MySQLToken.KW_SUBPARTITION) {// 一层分区
			throw new SQLSyntaxErrorException("table partition  Syntax Error ");
		}
		if (tk == MySQLToken.PUNC_LEFT_PAREN) {// 一层分区
			this.partitionLists = new ArrayList<Partition>();
			while (true) {
				Partition partition = parsePartition(mySQLDDLParser, lexer);
				if (partition == null)
					break;
				partitionLists.add(partition);
				if (lexer.token() == MySQLToken.PUNC_RIGHT_PAREN) {
					lexer.nextToken();
					break;
				}
			}
		} else {// 二层分区
			lexer.nextToken();
			if (lexer.token() != MySQLToken.KW_BY) {
				throw new SQLSyntaxErrorException("table subpartition Missing by keyword ");
			}
			lexer.nextToken();
			tk = lexer.token();
			if (tk == MySQLToken.KW_LINEAR || tk == MySQLToken.KW_COLUMNS) {
				if (tk == MySQLToken.KW_COLUMNS)
					subHaveColumnsKey = true;
				lexer.nextToken();
			}
			tk = lexer.token();
			lexer.nextToken();
			switch (tk) {
			case KW_RANGE:
				subPartitionType = PartitionType.RANGE;
				break;
			case KW_LIST:
				subPartitionType = PartitionType.LIST;
				break;
			case KW_HASH:
				subPartitionType = PartitionType.HASH;

				break;
			case KW_KEY:
				subPartitionType = PartitionType.KEY;

				break;
			default:
				throw new SQLSyntaxErrorException("table subpartition type error[range,list,hash,key] "
						+ lexer.stringValueUppercase());
			}
			if (lexer.token() != MySQLToken.PUNC_LEFT_PAREN) {
				throw new SQLSyntaxErrorException("table subpartition type column error Missing parenthesis");
			}
			lexer.nextToken();
			if (lexer.token() != MySQLToken.IDENTIFIER) {
				throw new SQLSyntaxErrorException("table subpartition type column is keyword "
						+ MySQLToken.keyWordToString(lexer.token()));
			}
			subPartitionColumn = mySQLDDLParser.identifier();
			if (lexer.token() != MySQLToken.PUNC_RIGHT_PAREN) {
				throw new SQLSyntaxErrorException("table subpartition type column error ");
			}
			lexer.nextToken();
			tk = lexer.token();
			if (subPartitionType == PartitionType.HASH || subPartitionType == PartitionType.KEY) {
				if (tk == MySQLToken.KW_PARTITIONS) {// PARTITIONS 4;
					lexer.nextToken();
					if (lexer.token() != MySQLToken.LITERAL_NUM_PURE_DIGIT)
						throw new SQLSyntaxErrorException("table partition size is not int ");
					partitionSize = lexer.integerValue().intValue();
					lexer.nextToken();
				}
			}
			tk = lexer.token();
			if (tk != MySQLToken.PUNC_LEFT_PAREN) {// 分区开始
				throw new SQLSyntaxErrorException("table subpartition  Syntax Error ");
			}
			this.partitionLists = new ArrayList<Partition>();
			while (true) {
				Partition partition = parsePartition(mySQLDDLParser, lexer);
				if (partition == null)
					break;
				partitionLists.add(partition);
				if (lexer.token() == MySQLToken.PUNC_RIGHT_PAREN) {
					lexer.nextToken();
					break;
				}
			}
		}
		if (subPartitionType == PartitionType.LIST || subPartitionType == PartitionType.RANGE) {
			if (partitionLists.size() == 0) {
				throw new SQLSyntaxErrorException("table partition list is null");
			} else if (this.subPartitionColumn != null) {
				if ((this.partitionSize == 0)
						&& (this.partitionLists.get(0) == null || this.partitionLists.get(0).subLists.size() == 0)) {
					throw new SQLSyntaxErrorException("table subpartition list is null and no partitions set");
				}
			}
		} else {
			if ((this.partitionSize == 0) && (this.partitionLists.size() == 0)) {
				throw new SQLSyntaxErrorException("table partition list is null and no partitions set");
			}
		}

	}

	Partition parsePartition(MySQLDDLParser mySQLDDLParser, MySQLLexer lexer) throws SQLSyntaxErrorException {
		return parsePartition(mySQLDDLParser, lexer, 0);
	}

	Partition parsePartition(MySQLDDLParser mySQLDDLParser, MySQLLexer lexer, int level) throws SQLSyntaxErrorException {
		MySQLToken tk = lexer.token();
		if ((tk == MySQLToken.EOF)
				|| (tk != MySQLToken.PUNC_LEFT_PAREN && tk != MySQLToken.PUNC_DOT && tk != MySQLToken.PUNC_COMMA)) {
			// if (tk == MySQLToken.PUNC_RIGHT_PAREN) {
			// lexer.nextToken();
			// }
			return null;
		}

		lexer.nextToken();
		tk = lexer.token();
		if (tk != MySQLToken.KW_PARTITION && tk != MySQLToken.KW_SUBPARTITION) {
			return null;
		}
		lexer.nextToken();
		if (lexer.token() != MySQLToken.IDENTIFIER) {
			throw new SQLSyntaxErrorException("table partition List Syntax Error ");
		}
		// Identifier id
		Partition partition = new Partition();
		partition.parName = mySQLDDLParser.identifier();
		if (!Character.isLetter(partition.parName.getIdText().charAt(0))) {
			throw new SQLSyntaxErrorException("table partition name Syntax Error Need to begin with the letter");
		}
		PartitionType thisType = level == 0 ? this.partitionType : this.subPartitionType;
		switch (thisType) {
		case RANGE:
			if (lexer.token() != MySQLToken.KW_VALUES) {
				throw new SQLSyntaxErrorException("table partition values Syntax Error missing values keyword");
			}
			lexer.nextToken();
			if (lexer.token() != MySQLToken.KW_LESS) {
				throw new SQLSyntaxErrorException("table partition values Syntax Error missing less keyword");
			}
			lexer.nextToken();
			if (lexer.token() != MySQLToken.KW_THAN) {
				throw new SQLSyntaxErrorException("table partition values Syntax Error missing then keyword");
			}
			lexer.nextToken();
			if (lexer.token() != MySQLToken.PUNC_LEFT_PAREN) {
				throw new SQLSyntaxErrorException("table partition values Syntax Error missing then keyword");
			}
			partition.parValue = mySQLDDLParser.exprParser.expression();
			break;
		case LIST:
			if (lexer.token() != MySQLToken.KW_VALUES) {
				throw new SQLSyntaxErrorException("table partition values Syntax Error missing values keyword");
			}
			lexer.nextToken();
			if (lexer.token() != MySQLToken.KW_IN) {
				throw new SQLSyntaxErrorException("table partition values Syntax Error missing values keyword");
			}
			lexer.nextToken();
			if (lexer.token() != MySQLToken.PUNC_LEFT_PAREN) {
				throw new SQLSyntaxErrorException("table partition values Syntax Error missing then keyword");
			}
			partition.parValue = mySQLDDLParser.exprParser.expression();
			break;
		case HASH:
			break;
		case KEY:
			break;
		default:
			break;
		}
		tk = lexer.token();
		if (tk == MySQLToken.PUNC_RIGHT_PAREN) {// 分区结束括号
			lexer.nextToken();
			return partition;
		} else if (tk == MySQLToken.PUNC_DOT || lexer.token() == MySQLToken.PUNC_COMMA) {// 只可能是一层分区列表
			return partition;
		}
		while (true) {
			boolean breaked = false;
			switch (tk) {
			case IDENTIFIER: {
				SpecialIdentifier si = MySQLDDLParser.specialIdentifiers.get(lexer.stringValueUppercase());
				if (si == SpecialIdentifier.DATA) {
					lexer.nextToken();
					mySQLDDLParser.matchIdentifier("DIRECTORY");
					if (lexer.token() == OP_EQUALS) {
						lexer.nextToken();
					}
					partition.dataDir = (LiteralString) mySQLDDLParser.exprParser.expression();
				} else if (si == SpecialIdentifier.ENGINE) {
					if (lexer.nextToken() == OP_EQUALS) {
						lexer.nextToken();
					}
					partition.engine = mySQLDDLParser.identifier();
				} else {
					throw new SQLSyntaxErrorException("table partition Syntax Error  Unsupported keyword");
				}
				break;
			}
			case KW_INDEX: {
				lexer.nextToken();
				if (lexer.token() == IDENTIFIER && "DIRECTORY".equals(lexer.stringValueUppercase())) {
					if (lexer.nextToken() == OP_EQUALS) {
						lexer.nextToken();
					}
					partition.indexDir = (LiteralString) mySQLDDLParser.exprParser.expression();
				} else {
					throw new SQLSyntaxErrorException("table partition index DIRECTORY  Syntax Error ");
				}
				break;
			}
			case PUNC_RIGHT_PAREN:// 分区结束括号
				return partition;
			case PUNC_LEFT_PAREN:// 子分区
				breaked = true;
				break;
			case PUNC_COMMA:// 下一个分区
			case PUNC_DOT:// 下一个分区
				return partition;
			default:
				throw new SQLSyntaxErrorException("table partition list Syntax Error " + lexer.stringValueUppercase());
			}
			if (breaked)
				break;
			tk = lexer.token();
		}
		tk = lexer.token();
		while (true) {
			Partition subpartition = parsePartition(mySQLDDLParser, lexer, level + 1);
			if (subpartition == null)
				break;
			if (partition.subLists == null) {
				partition.subLists = new ArrayList<Partition>();
			}
			partition.subLists.add(subpartition);
			if (lexer.token() == MySQLToken.PUNC_RIGHT_PAREN) {
				lexer.nextToken();
				break;
			}
		}
		return partition;
		// throw new SQLSyntaxErrorException("table partition of FORMAT error");
	}

	@Override
	public void accept(SQLASTVisitor visitor) {
		// visitor.visit(this);
	}

}
