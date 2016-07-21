package com.alibaba.cobar.mysql.bio.executor;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.sql.SQLNonTransientException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;

import com.alibaba.cobar.CobarServer;
import com.alibaba.cobar.config.ErrorCode;
import com.alibaba.cobar.config.model.SchemaConfig;
import com.alibaba.cobar.mysql.BufferUtil;
import com.alibaba.cobar.mysql.CharsetUtil;
import com.alibaba.cobar.mysql.MySQLMessage;
import com.alibaba.cobar.mysql.PacketUtil;
import com.alibaba.cobar.mysql.bio.Channel;
import com.alibaba.cobar.mysql.bio.MySQLChannel;
import com.alibaba.cobar.mysql.bio.executor.MultiNodeExecutor.BinaryErrInfo;
import com.alibaba.cobar.net.FrontendConnection;
import com.alibaba.cobar.net.mysql.BinaryPacket;
import com.alibaba.cobar.net.mysql.EOFPacket;
import com.alibaba.cobar.net.mysql.ErrorPacket;
import com.alibaba.cobar.net.mysql.FieldPacket;
import com.alibaba.cobar.net.mysql.MySQLPacket;
import com.alibaba.cobar.net.mysql.OkPacket;
import com.alibaba.cobar.parser.ast.expression.Expression;
import com.alibaba.cobar.parser.ast.expression.primary.Identifier;
import com.alibaba.cobar.parser.ast.expression.primary.function.groupby.Avg;
import com.alibaba.cobar.parser.ast.expression.primary.function.groupby.Count;
import com.alibaba.cobar.parser.ast.expression.primary.function.groupby.Max;
import com.alibaba.cobar.parser.ast.expression.primary.function.groupby.Min;
import com.alibaba.cobar.parser.ast.expression.primary.function.groupby.Sum;
import com.alibaba.cobar.parser.ast.expression.primary.literal.LiteralNumber;
import com.alibaba.cobar.parser.ast.fragment.Limit;
import com.alibaba.cobar.parser.ast.fragment.OrderBy;
import com.alibaba.cobar.parser.ast.fragment.SortOrder;
import com.alibaba.cobar.parser.ast.stmt.SQLStatement;
import com.alibaba.cobar.parser.ast.stmt.dml.DMLSelectStatement;
import com.alibaba.cobar.parser.recognizer.SQLParserDelegate;
import com.alibaba.cobar.parser.recognizer.mysql.syntax.MySQLParser;
import com.alibaba.cobar.parser.util.Pair;
import com.alibaba.cobar.route.RouteResultset;
import com.alibaba.cobar.route.RouteResultsetNode;
import com.alibaba.cobar.route.ServerRouter;
import com.alibaba.cobar.route.visitor.PartitionKeyVisitor;
import com.alibaba.cobar.server.ServerConnection;
import com.alibaba.cobar.server.session.BlockingSession;

public class MultiNodeComDataExecutor {
	private static final Logger LOGGER = Logger.getLogger(MultiNodeComDataExecutor.class);
	static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	static final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd");
	static final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss");
	static final Date nullDate = new Date(0);

	static final DecimalFormat dcdf[] = new DecimalFormat[] { new DecimalFormat("#.#"), new DecimalFormat("#.##"),
			new DecimalFormat("#.###"), new DecimalFormat("#.####"), new DecimalFormat("#.#####"),
			new DecimalFormat("#.######"), new DecimalFormat("#.#######"), new DecimalFormat("#.########"),
			new DecimalFormat("#.#########"), new DecimalFormat("#.##########"), new DecimalFormat("#.###########"),
			new DecimalFormat("#.############"), new DecimalFormat("#.#############"),
			new DecimalFormat("#.##############"), new DecimalFormat("#.###############") };

	static String COM_GROUP_PREFIX = "/*!comjoin(";
	static String COM_LIMIT_PREFIX = "limit(";
	public static String constCountName = "__count";
	private int countIndex = -1;
	private int comLimits = 10000;
	private int returnStartIndex = -1;
	private int returnEndIndex = -1;

	private String orgSql = null;
	private String execSql = null;
	private ServerConnection sc = null;
	private SQLStatement ast = null;
	private PartitionKeyVisitor visitor = null;
	private boolean parsed = false;
	// private RouteResultset rrs = null;
	private boolean needCom = false;
	List<Pair<Expression, String>> selExprList = null;
	TableData tbData = null;
	private int fieldMothed[] = null;
	public static final int maxDbLimit = 100000;
	private OrderBy order = null;
	public boolean isAddCountField = false;

	public static class TableData {
		final MultiNodeComDataExecutor comExe;
		static final Charset[] cacheCharset = new Charset[99];
		// 平均值字段位置
		FieldPacket[] fieldPacket = null;
		Charset[] fieldCs = null;
		int srcFieldLen = 0;
		int destFieldLen = 0;
		List<BinaryPacket> headerList = null;

		HashMap<String, CacheRow> cacheData = new HashMap<String, CacheRow>();
		static {
			for (int i = 0; i < 99; i++) {
				try {
					cacheCharset[i] = null;
					cacheCharset[i] = Charset.forName(CharsetUtil.getCharset(i));
				} catch (Exception e) {
				}
			}
		}

		public void clear() {
			cacheData.clear();
		}

		public TableData(MultiNodeComDataExecutor comExe) {
			this.comExe = comExe;
			srcFieldLen = comExe.selExprList.size();
			destFieldLen = srcFieldLen;
			Pair<Expression, String> last = comExe.selExprList.get(srcFieldLen - 1);
			if (constCountName.equals(last.getValue())) {
				destFieldLen--;
			}
		}

		public void setHeader(List<BinaryPacket> headerList) {
			try {
				long st = System.nanoTime();
				fieldPacket = new FieldPacket[srcFieldLen];
				fieldCs = new Charset[srcFieldLen];
				this.headerList = headerList;
				// BinaryPacket bin = headerList.get(0);
				// MySQLMessage msg = new MySQLMessage(bin.data);
				// long colLength = msg.readLength();
				// System.out.println("colLength=" + colLength);
				for (int i = 0; i < fieldPacket.length; i++) {
					fieldPacket[i] = new FieldPacket();
					fieldPacket[i].read(headerList.get(i + 1));
					/** 需要确定是否可删除 */
					// System.out.println("fieldPacket[" + i + "].packetLength"
					// +
					// fieldPacket[i].packetLength);
					// fieldPacket[i].packetLength =
					// fieldPacket[i].calcPacketSize();
					// // System.out.println("fieldPacket[" + i +
					// "].packetLength" +
					// fieldPacket[i].packetLength);
					int chIndex = fieldPacket[i].charsetIndex >= 0 && fieldPacket[i].charsetIndex <= 98 ? fieldPacket[i].charsetIndex
							: 83;
					fieldCs[i] = cacheCharset[chIndex];
					if (LOGGER.isDebugEnabled()) {
						LOGGER.debug("column:" + i + " type=" + this.fieldPacket[i].type + " name="
								+ new String(fieldPacket[i].name) + " charset="
								+ TableData.cacheCharset[fieldPacket[i].charsetIndex] + " flags="
								+ fieldPacket[i].flags + " length=" + fieldPacket[i].length + " decimals="
								+ fieldPacket[i].decimals);
					}
				}
				// System.out.println("setHeader(List<BinaryPacket> headerList) " + (System.nanoTime() - st) / 1000000
				// + "ms");
			} catch (Exception e) {
				LOGGER.error(e.getMessage(), e);
			}
		}

		public void addRow(BinaryPacket bin) {
			CacheRow nrow = new CacheRow(this, bin);
			CacheRow row = null;
			synchronized (cacheData) {
				row = cacheData.get(nrow.key);
				if (row == null) {
					cacheData.put(nrow.key, nrow);
					return;
				}
			}
			synchronized (row) {// calc:srow + row
				comRow(row, nrow);
			}
		}

		public void comRow(CacheRow row, CacheRow nrow) {
			for (int i = 0; i < row.colVals.length; i++) {
				switch (comExe.fieldMothed[i]) {
				case 1:// Count
					count(row, nrow, i);
					break;
				case 2:// sum
					sum(row, nrow, i);
					break;
				case 3:// max
					max(row, nrow, i);
					break;
				case 4:// min
					min(row, nrow, i);
					break;
				case 5:// avg
					avg(row, nrow, i);
					break;
				}
			}
		}

		public void avg(CacheRow row, CacheRow nrow, int i) {// 防止溢出，降低性能，实行增量计算 // 读取值时已经转换为double
			// ///count index columns
			long count = (Long) row.colVals[comExe.countIndex];
			long ncount = (Long) nrow.colVals[comExe.countIndex];
			double avg = 0;
			Double c = (Double) row.colVals[i];
			Double nc = (Double) nrow.colVals[i];
			if (c > 10000000000000l || nc > 10000000000000l) {
				avg = c + ((nc - c) * (ncount / 10000)) / (count + ncount) / 1000;
			} else {
				avg = c + ((nc - c) * ncount) / (count + ncount);
			}
			// avg = (c * count + nc * ncount) / (count + ncount);// 可能会溢出
			row.colVals[i] = avg;
		}

		public void min(CacheRow row, CacheRow nrow, int i) {
			Class<?> cls = row.colVals[i].getClass();
			if (cls.equals(Long.class) || cls.equals(long.class)) {
				Long c = (Long) row.colVals[i];
				Long nc = (Long) nrow.colVals[i];
				row.colVals[i] = c < nc ? c : nc;
			} else if (cls.equals(Integer.class) || cls.equals(int.class)) {
				Integer c = (Integer) row.colVals[i];
				Integer nc = (Integer) nrow.colVals[i];
				row.colVals[i] = c < nc ? c : nc;
			} else if (cls.equals(Double.class) || cls.equals(double.class)) {
				Double c = (Double) row.colVals[i];
				Double nc = (Double) nrow.colVals[i];
				row.colVals[i] = c < nc ? c : nc;
			} else if (cls.equals(Byte.class) || cls.equals(byte.class)) {
				Byte c = (Byte) row.colVals[i];
				Byte nc = (Byte) nrow.colVals[i];
				row.colVals[i] = c < nc ? c : nc;
			} else if (cls.equals(String.class)) {
				String c = (String) row.colVals[i];
				String nc = (String) nrow.colVals[i];
				row.colVals[i] = c.compareTo(nc) > 0 ? c : nc;
			} else if (cls.equals(Date.class)) {
				Date c = (Date) row.colVals[i];
				Date nc = (Date) nrow.colVals[i];
				row.colVals[i] = c.compareTo(nc) < 0 ? c : nc;
			} else {
				String c = row.colVals[i].toString();
				String nc = nrow.colVals[i].toString();
				row.colVals[i] = c.compareTo(nc) < 0 ? c : nc;
			}
		}

		public void max(CacheRow row, CacheRow nrow, int i) {
			Class<?> cls = row.colVals[i].getClass();
			if (cls.equals(Long.class) || cls.equals(long.class)) {
				Long c = (Long) row.colVals[i];
				Long nc = (Long) nrow.colVals[i];
				row.colVals[i] = c > nc ? c : nc;
			} else if (cls.equals(Integer.class) || cls.equals(int.class)) {
				Integer c = (Integer) row.colVals[i];
				Integer nc = (Integer) nrow.colVals[i];
				row.colVals[i] = c > nc ? c : nc;
			} else if (cls.equals(Double.class) || cls.equals(double.class)) {
				Double c = (Double) row.colVals[i];
				Double nc = (Double) nrow.colVals[i];
				row.colVals[i] = c > nc ? c : nc;
			} else if (cls.equals(Byte.class) || cls.equals(byte.class)) {
				Byte c = (Byte) row.colVals[i];
				Byte nc = (Byte) nrow.colVals[i];
				row.colVals[i] = c > nc ? c : nc;
			} else if (cls.equals(String.class)) {
				String c = (String) row.colVals[i];
				String nc = (String) nrow.colVals[i];
				row.colVals[i] = c.compareTo(nc) > 0 ? c : nc;
			} else if (cls.equals(Date.class)) {
				Date c = (Date) row.colVals[i];
				Date nc = (Date) nrow.colVals[i];
				row.colVals[i] = c.compareTo(nc) > 0 ? c : nc;
			} else {
				String c = row.colVals[i].toString();
				String nc = nrow.colVals[i].toString();
				row.colVals[i] = c.compareTo(nc) > 0 ? c : nc;
			}
		}

		public void sum(CacheRow row, CacheRow nrow, int i) {
			Class<?> cls = row.colVals[i].getClass();
			if (cls.equals(Long.class) || cls.equals(long.class)) {
				Long c = (Long) row.colVals[i];
				Long nc = null;
				Class<?> ncls = nrow.colVals[i].getClass();
				if (ncls.equals(Long.class) || ncls.equals(long.class)) {
					nc = (Long) nrow.colVals[i];
				} else if (ncls.equals(Integer.class) || ncls.equals(Integer.class)) {
					nc = ((Integer) nrow.colVals[i]).longValue();
				} else if (ncls.equals(Byte.class) || ncls.equals(byte.class)) {
					nc = ((Byte) nrow.colVals[i]).longValue();
				} else {
					nc = Long.parseLong(nrow.colVals[i].toString());
				}
				row.colVals[i] = c + nc;
			} else if (cls.equals(Integer.class) || cls.equals(int.class)) {
				Integer c = (Integer) row.colVals[i];
				Integer nc = (Integer) nrow.colVals[i];
				row.colVals[i] = c.longValue() + nc;
			} else if (cls.equals(Double.class) || cls.equals(double.class)) {
				Double c = (Double) row.colVals[i];
				Double nc = null;
				Class<?> ncls = nrow.colVals[i].getClass();
				if (ncls.equals(Double.class) || ncls.equals(double.class)) {
					nc = (Double) nrow.colVals[i];
				} else {
					nc = Double.parseDouble(nrow.colVals[i].toString());
				}
				row.colVals[i] = c + nc;
			} else if (cls.equals(Byte.class) || cls.equals(byte.class)) {
				Byte c = (Byte) row.colVals[i];
				Byte nc = (Byte) nrow.colVals[i];
				row.colVals[i] = c.longValue() + nc;
			} else {
				Double c = Double.parseDouble(row.colVals[i].toString());
				Double nc = Double.parseDouble(nrow.colVals[i].toString());
				row.colVals[i] = c + nc;
			}
		}

		public void count(CacheRow row, CacheRow nrow, int i) {// 读取值时已经转换为long
			Long c = (Long) row.colVals[i];
			Long nc = (Long) nrow.colVals[i];
			row.colVals[i] = c + nc;

			// Class<?> cls = row.colVals[i].getClass();
			// if (cls.equals(Long.class) || cls.equals(long.class)) {
			// Long c = (Long) row.colVals[i];
			// Long nc = null;
			// Class<?> ncls = nrow.colVals[i].getClass();
			// if (ncls.equals(Long.class) || ncls.equals(long.class)) {
			// nc = (Long) nrow.colVals[i];
			// } else {
			// nc = Long.parseLong(nrow.colVals[i].toString());
			// }
			// row.colVals[i] = c + nc;
			// } else if (cls.equals(Integer.class) || cls.equals(int.class)) {
			// Integer c = (Integer) row.colVals[i];
			// Integer nc = (Integer) nrow.colVals[i];
			// row.colVals[i] = c.longValue() + nc;
			// } else {
			// Long c = Long.parseLong(row.colVals[i].toString());
			// Long nc = Long.parseLong(nrow.colVals[i].toString());
			// row.colVals[i] = c + nc;
			// }
		}

		// 根据 SQL中指定的排序字段排序
		private String[] sort() {
			if (this.comExe.order == null)
				return this.cacheData.keySet().toArray(new String[0]);
			List<Pair<Expression, SortOrder>> orlist = this.comExe.order.getOrderByList();
			List<Pair<Integer, SortOrder>> orderList = new ArrayList<Pair<Integer, SortOrder>>();
			for (Pair<Expression, SortOrder> pair : orlist) {
				Expression exp = pair.getKey();
				SortOrder so = pair.getValue();
				// System.out.println(exp);
				// System.out.println(so);
				// System.out.println(so.ordinal());
				if (exp instanceof LiteralNumber) {
					LiteralNumber ln = (LiteralNumber) exp;
					int index = ln.getNumber().intValue() - 1;
					// System.out.println("index=" + index);
					orderList.add(new Pair<Integer, SortOrder>(index, so));
				} else if (exp instanceof Identifier) {
					Identifier ln = (Identifier) exp;
					String colname = ln.getIdText().toUpperCase();
					int index = -1;
					for (int i = 0; i < this.comExe.selExprList.size(); i++) {
						Pair<Expression, String> p = this.comExe.selExprList.get(i);
						if (p.getValue() != null && colname.equals(p.getValue().toUpperCase())) {
							index = i;
							break;
						}
						Expression e = p.getKey();
						if (e instanceof Identifier) {
							Identifier c = (Identifier) e;
							String cn = c.getIdText().toUpperCase();
							if (colname.equals(cn)) {
								index = i;
								break;
							}
						}
					}
					// System.out.println("colname=" + colname + "  index=" + index);
					if (index != -1) {
						orderList.add(new Pair<Integer, SortOrder>(index, so));
					}
				}
			}
			if (orderList.size() > 0) {
				return comExe.sort(orderList);
			} else {
				return this.cacheData.keySet().toArray(new String[0]);
			}
		}
	}

	public static class CacheRow {
		public String key = null;
		public Object[] colVals = null;

		public CacheRow(TableData tab, BinaryPacket bin) {
			colVals = new Object[tab.srcFieldLen];
			setValue(tab, bin);
		}

		public void setValue(TableData tab, BinaryPacket bin) {
			try {
				RowDataPacket row = new RowDataPacket(tab.srcFieldLen);
				row.read(bin);
				StringBuilder key = new StringBuilder();
				for (int i = 0; i < tab.srcFieldLen; i++) {
					colVals[i] = getDeVal(tab, row, i);
					if (tab.comExe.fieldMothed[i] == 0) {// group by col
						if (this.colVals[i] != null) {
							key.append(this.colVals[i].toString());
						}
						key.append("#");
					} else if (tab.comExe.fieldMothed[i] == 1) {// count
						Class<?> cls = colVals[i].getClass();
						if (!cls.equals(Long.class)) {
							colVals[i] = Long.parseLong(colVals[i].toString());
						}
					} else if (tab.comExe.fieldMothed[i] == 5) {// count
						Class<?> cls = colVals[i].getClass();
						if (!cls.equals(Double.class)) {
							colVals[i] = Double.parseDouble(colVals[i].toString());
						}
					}
				}

				if (key.length() > 0) {
					this.key = key.toString();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		public String getString(TableData tab, RowDataPacket row, int index) {
			if (tab.fieldCs[index] == null) {
				return new String(row.fieldValues[index]);
			} else {
				return new String(row.fieldValues[index], tab.fieldCs[index]);
			}
		}

		public Object getDeVal(TableData tab, RowDataPacket row, int index) {
			try {
				// System.out.println("column:" + index + " type=" + tab.fieldPacket[index].type + " name="
				// + new String(tab.fieldPacket[index].name) + " charset="
				// + TableData.cacheCharset[tab.fieldPacket[index].charsetIndex] + ">"
				// + CharsetUtil.getCharset(tab.fieldPacket[index].charsetIndex) + " flags="
				// + tab.fieldPacket[index].flags + " deflength=" + tab.fieldPacket[index].length + " decimals="
				// + tab.fieldPacket[index].decimals + " vallength=" + row.fieldValues[index].length);
				boolean isNull = (row.fieldValues[index] == null || row.fieldValues[index].length == 0);
				switch (tab.fieldPacket[index].type) {
				case MysqlFieldType.FIELD_TYPE_LONGLONG: // 8:bigint
					if (isNull)
						return 0;
					return Long.parseLong(new String(row.fieldValues[index]));
				case MysqlFieldType.FIELD_TYPE_STRING:
					// 254:BINARY CHAR ENUM SET
					if (isNull)
						return "";
					return new String(row.fieldValues[index]);
				case MysqlFieldType.FIELD_TYPE_BIT: {// 16:bit
					if (isNull)
						return (byte) 0;
					return row.fieldValues[index][0];
				}
				case MysqlFieldType.FIELD_TYPE_TINY: {// 1:BOOL BOOLEN TINYINT
					if (isNull)
						return (byte) 0;
					String val = new String(row.fieldValues[index]);
					return Byte.parseByte(val);
				}
				// 252:BLOB LONGBLOB LONGTEXT MEDIUMBOLOB MEDIUMTEXT TEXT TINYBOLB TINYTEXT
				case MysqlFieldType.FIELD_TYPE_BLOB:
				case MysqlFieldType.FIELD_TYPE_VAR_STRING: {// 253:VARBINARY VARCHAR
					if (isNull)
						return "";
					String val = getString(tab, row, index);
					return val;
				}
				case MysqlFieldType.FIELD_TYPE_DATETIME: // 12:DATETIME
				case MysqlFieldType.FIELD_TYPE_TIMESTAMP: {// 7：TIMESTAMP
					if (isNull)
						return nullDate;
					String val = new String(row.fieldValues[index]);
					return sdf.parse(val);
				}
				case MysqlFieldType.FIELD_TYPE_NEWDATE:// 14
				case MysqlFieldType.FIELD_TYPE_DATE: {// 10：DATE
					if (isNull)
						return nullDate;
					String val = new String(row.fieldValues[index]);
					return df.parse(val);
				}
				case MysqlFieldType.FIELD_TYPE_TIME: {// 11：TIME
					if (isNull)
						return nullDate;
					String val = new String(row.fieldValues[index]);
					return tf.parse(val);
				}
				case MysqlFieldType.FIELD_TYPE_DECIMAL: // DECIMAL
				case MysqlFieldType.FIELD_TYPE_NEWDECIMAL: // 246:DECIMAL NUMERIC
				case MysqlFieldType.FIELD_TYPE_DOUBLE: // 5:DOUBLE REAL
				case MysqlFieldType.FIELD_TYPE_FLOAT: {// 4:FLOAT
					if (isNull)
						return (double) 0.0;
					String val = new String(row.fieldValues[index]);
					return Double.parseDouble(val);
				}
				case MysqlFieldType.FIELD_TYPE_LONG: // 3:INT
				case MysqlFieldType.FIELD_TYPE_INT24: // 9 :MEDIUMINT
				case MysqlFieldType.FIELD_TYPE_SHORT: // 2:SMALLINT
				case MysqlFieldType.FIELD_TYPE_YEAR: {// 13:YEAR
					if (isNull)
						return 0;
					String val = new String(row.fieldValues[index]);
					return Integer.parseInt(val);
				}
				case MysqlFieldType.FIELD_TYPE_NULL:// 6
				case MysqlFieldType.FIELD_TYPE_VARCHAR:// 15
				case MysqlFieldType.FIELD_TYPE_ENUM:// 247
				case MysqlFieldType.FIELD_TYPE_SET:// 248
				case MysqlFieldType.FIELD_TYPE_TINY_BLOB:// 249
				case MysqlFieldType.FIELD_TYPE_MEDIUM_BLOB:// 250
				case MysqlFieldType.FIELD_TYPE_LONG_BLOB:// 251
				case MysqlFieldType.FIELD_TYPE_GEOMETRY:// 255
				default:
					if (isNull)
						return "";
					return new String(row.fieldValues[index]);
				}
			} catch (Exception e) {
				e.printStackTrace(System.out);
				return null;
			}
		}
	}

	public static class RowDataPacket extends MySQLPacket {
		private static final byte NULL_MARK = (byte) 251;
		public final int fieldCount;
		public final byte[][] fieldValues;

		public RowDataPacket(int fieldCount) {
			this.fieldCount = fieldCount;
			this.fieldValues = new byte[fieldCount][];
		}

		public void set(int index, byte[] value) {
			fieldValues[index] = value;
		}

		public void read(BinaryPacket bin) {
			packetLength = bin.packetLength;
			packetId = bin.packetId;
			MySQLMessage mm = new MySQLMessage(bin.data);
			for (int i = 0; i < fieldCount; i++) {
				fieldValues[i] = mm.readBytesWithLength();
			}
		}

		public void read(BinaryPacket bin, int len) {
			packetLength = bin.packetLength;
			packetId = bin.packetId;
			MySQLMessage mm = new MySQLMessage(bin.data);
			for (int i = 0; i < fieldCount && i < len; i++) {
				fieldValues[i] = mm.readBytesWithLength();
			}
		}

		@Override
		public ByteBuffer write(ByteBuffer bb, FrontendConnection c) {
			bb = c.checkWriteBuffer(bb, c.getPacketHeaderSize());
			BufferUtil.writeUB3(bb, calcPacketSize());
			bb.put(packetId);
			for (int i = 0; i < fieldCount; i++) {
				byte[] fv = fieldValues[i];
				if (fv == null || fv.length == 0) {
					bb = c.checkWriteBuffer(bb, 1);
					bb.put(RowDataPacket.NULL_MARK);
				} else {
					bb = c.checkWriteBuffer(bb, BufferUtil.getLength(fv.length));
					BufferUtil.writeLength(bb, fv.length);
					bb = c.writeToBuffer(fv, bb);
				}
			}
			return bb;
		}

		@Override
		public int calcPacketSize() {
			int size = 0;
			for (int i = 0; i < fieldCount; i++) {
				byte[] v = fieldValues[i];
				size += (v == null || v.length == 0) ? 1 : BufferUtil.getLength(v);
			}
			return this.packetLength = size;
		}

		@Override
		protected String getPacketInfo() {
			return "MySQL RowData Packet";
		}

	}

	// decimals 数值精度：该字段对DECIMAL和NUMERIC类型的数值字段有效，用于标识数值的精度（小数点位置）。
	public static class MysqlFieldType {
		static final int FIELD_TYPE_DECIMAL = 0x00;
		static final int FIELD_TYPE_TINY = 0x01;
		static final int FIELD_TYPE_SHORT = 0x02;
		static final int FIELD_TYPE_LONG = 0x03;
		static final int FIELD_TYPE_FLOAT = 0x04;
		static final int FIELD_TYPE_DOUBLE = 0x05;
		static final int FIELD_TYPE_NULL = 0x06;
		static final int FIELD_TYPE_TIMESTAMP = 0x07;
		static final int FIELD_TYPE_LONGLONG = 0x08;
		static final int FIELD_TYPE_INT24 = 0x09;
		static final int FIELD_TYPE_DATE = 0x0A;
		static final int FIELD_TYPE_TIME = 0x0B;
		static final int FIELD_TYPE_DATETIME = 0x0C;
		static final int FIELD_TYPE_YEAR = 0x0D;
		static final int FIELD_TYPE_NEWDATE = 0x0E;
		static final int FIELD_TYPE_VARCHAR = 0x0F;
		static final int FIELD_TYPE_BIT = 0x10;
		static final int FIELD_TYPE_NEWDECIMAL = 0xF6;
		static final int FIELD_TYPE_ENUM = 0xF7;
		static final int FIELD_TYPE_SET = 0xF8;
		static final int FIELD_TYPE_TINY_BLOB = 0xF9;
		static final int FIELD_TYPE_MEDIUM_BLOB = 0xFA;
		static final int FIELD_TYPE_LONG_BLOB = 0xFB;
		static final int FIELD_TYPE_BLOB = 0xFC;
		static final int FIELD_TYPE_VAR_STRING = 0xFD;
		static final int FIELD_TYPE_STRING = 0xFE;
		static final int FIELD_TYPE_GEOMETRY = 0xFF;
	}

	public static class MysqlFieldFlag {
		static final int NOT_NULL_FLAG = 0x0001;
		static final int PRI_KEY_FLAG = 0x0002;
		static final int UNIQUE_KEY_FLAG = 0x0004;
		static final int MULTIPLE_KEY_FLAG = 0x0008;
		static final int BLOB_FLAG = 0x0010;
		static final int UNSIGNED_FLAG = 0x0020;
		static final int ZEROFILL_FLAG = 0x0040;
		static final int BINARY_FLAG = 0x0080;
		static final int ENUM_FLAG = 0x0100;
		static final int AUTO_INCREMENT_FLAG = 0x0200;
		static final int TIMESTAMP_FLAG = 0x0400;
		static final int SET_FLAG = 0x0800;
	}

	public boolean getParsed() {
		return this.needCom && this.parsed;
	}

	public void setNeedCom(boolean needCom) {
		this.needCom = needCom;
	}

	public SQLStatement getAst() {
		return ast;
	}

	public PartitionKeyVisitor getVisitor() {
		return visitor;
	}

	public String getExecSql() {
		return execSql;
	}

	public Object getOrgSql() {
		return orgSql;
	}

	// public void setAst(SQLStatement ast) {
	// this.ast = ast;
	// }
	//
	// public void setVisitor(PartitionKeyVisitor visitor) {
	// this.visitor = visitor;
	// }

	private MultiNodeComDataExecutor(String sql, ServerConnection c) {
		this.sc = c;
		this.orgSql = sql;
		this.execSql = sql;
	}

	public static MultiNodeComDataExecutor getComDataExecutor(RouteResultset rrs, SQLStatement ast,
			PartitionKeyVisitor visitor, String sql, ServerConnection c) throws SQLNonTransientException {
		// long l = System.nanoTime();
		MultiNodeComDataExecutor com = new MultiNodeComDataExecutor(sql, c);
		com.parsed = false;
		if (ast != null && visitor != null) {
			com.ast = ast;
			com.visitor = visitor;
		} else {
			com.parseAst();
		}
		com.init();
		// System.out.println("parse comdata time:" + (System.nanoTime() - l) / 1000000 + "ms");
		return com;
	}

	// 解析是否包含合并命令，识别参数，解析SQL识别记录数字段
	public static MultiNodeComDataExecutor parse(String sql, ServerConnection c) throws SQLNonTransientException {
		// long l = System.nanoTime();
		MultiNodeComDataExecutor com = new MultiNodeComDataExecutor(sql, c);
		com.parseParams(sql);
		if (com.parsed) {
			if (LOGGER.isDebugEnabled())
				LOGGER.debug("parse comsql:" + sql);
			// System.out.println("countIndex=" + com.countIndex);
			// System.out.println("comLimits=" + com.comLimits);
			// System.out.println("returnStartIndex=" + com.returnStartIndex);
			// System.out.println("returnEndIndex=" + com.returnEndIndex);
			com.parsed = false;
			com.parseAst();
			com.init();
			// System.out.println("parse comdata time:" + (System.nanoTime() - l) / 1000000 + "ms");
			return com;
		} else {
			return null;
		}
	}

	public void init() throws SQLNonTransientException {
		try {
			if (!(ast instanceof DMLSelectStatement)) {
				needCom = false;
				return;
			}
			// 判断是否存在记录数字段
			boolean isInGroupQuery = false;
			List<Integer> countIndexs = new ArrayList<Integer>();
			List<Integer> avgIndexs = new ArrayList<Integer>();
			DMLSelectStatement dmlselt = (DMLSelectStatement) ast;
			selExprList = dmlselt.getSelectExprList();
			int index = -1;
			// dmlselt.getGroup()
			this.order = dmlselt.getOrder();
			Limit limit = dmlselt.getLimit();
			if (limit != null) {
				if (this.returnEndIndex == -1 && this.returnStartIndex == -1) {
					Object offset = limit.getOffset();
					Object size = limit.getSize();
					int oset = -1;
					int osize = -1;
					if (offset != null && offset instanceof Number) {
						oset = ((Number) offset).intValue();
					}
					if (size != null && size instanceof Number) {
						osize = ((Number) size).intValue();
					}
					if (oset != -1) {
						this.returnStartIndex = oset;
					}
					if (osize != -1) {
						this.returnEndIndex = osize + (oset == -1 ? 0 : oset);
					}
					if (this.returnStartIndex < this.returnEndIndex) {
						int t = this.returnEndIndex;
						this.returnEndIndex = this.returnStartIndex;
						this.returnStartIndex = t;
					}
				}
			}
			fieldMothed = new int[selExprList.size() + 1];
			for (int i = 0; i < fieldMothed.length - 1; i++) {
				Pair<Expression, String> pair = selExprList.get(i);
				fieldMothed[i] = 0;
				Expression exp = pair.getKey();
				index++;
				if (exp instanceof Count) {
					if (!((Count) exp).isDistinct()) {
						countIndexs.add(index);
					}
					fieldMothed[i] = 1;
					isInGroupQuery = true;
				} else if (exp instanceof Sum) {
					fieldMothed[i] = 2;
					isInGroupQuery = true;
				} else if (exp instanceof Max) {
					fieldMothed[i] = 3;
					isInGroupQuery = true;
				} else if (exp instanceof Min) {
					fieldMothed[i] = 4;
					isInGroupQuery = true;
				} else if (exp instanceof Avg) {
					fieldMothed[i] = 5;
					avgIndexs.add(index);
					isInGroupQuery = true;
				}
			}
			if (!isInGroupQuery && dmlselt.getGroup() != null) {
				isInGroupQuery = true;
			}
			if (!isInGroupQuery) {
				this.parsed = false;
				return;
			}
			// //
			// ##########################test#######################################
			// TableReferences tbref = dmlselt.getTables();
			// List<TableReference> tbRefLst = tbref.getTableReferenceList();
			// for (TableReference tableReference : tbRefLst) {
			// if (tableReference instanceof TableRefFactor) {
			// TableRefFactor tbrf = (TableRefFactor) tableReference;
			//
			// } else if (tableReference instanceof SubqueryFactor) {
			// SubqueryFactor tbrf = (SubqueryFactor) tableReference;
			// QueryExpression tbqx = tbrf.getSubquery();
			// if (tbqx instanceof DMLSelectUnionStatement) {
			// // DMLSelectUnionStatement dmlselt =
			// // (DMLSelectUnionStatement)tbqx;
			// // List<DMLSelectStatement> dmlst =
			// // dmlselt.getSelectStmtList();
			// // for (DMLSelectStatement dmlSelectStatement :
			// // dmlst) {
			// // List<Pair<Expression, String>> selExprList =
			// // dmlSelectStatement.getSelectExprList();
			// // for (Pair<Expression, String> pair :
			// // selExprList) {
			// // System.out.println(pair.getKey() + "  " +
			// // pair.getValue());
			// // }
			// // }
			// }
			// } else if (tableReference instanceof InnerJoin) {
			// } else if (tableReference instanceof OuterJoin) {
			// } else if (tableReference instanceof NaturalJoin) {
			// } else if (tableReference instanceof StraightJoin) {
			// } else if (tableReference instanceof Dual) {
			// }
			// }
			// // 访问表
			// Map<String, Map<String, List<Object>>> astExt =
			// visitor.getColumnValue();
			// for (Entry<String, Map<String, List<Object>>> e :
			// astExt.entrySet()) {
			// Map<String, List<Object>> col2Val = e.getValue();
			// String vtb = e.getKey();
			// }
			// // #############################end
			// // test####################################
			if (this.countIndex >= 0) {
				if (!countIndexs.contains(this.countIndex)) {
					throw new Exception("指定的记录数索引在查询语句中不存在");
				}
			} else {
				// 解析SQL是否存在行记录统计,不存在时判断是否存在avg字段，如果存在，则添加记录数字段,生成新的执行SQL
				if (countIndexs.size() == 0) {// 不存在,判断是否存在AVG字段
					if (avgIndexs.size() > 0) {// 添加count
						// 解析添加记录数字段
						Expression count = new Count(new Identifier(null, "0"));
						Pair<Expression, String> c = new Pair<Expression, String>(count, constCountName);
						this.countIndex = selExprList.size();
						fieldMothed[countIndex] = 1;
						selExprList.add(c);
						this.execSql = ServerRouter.genSQL(ast, this.execSql);
						isAddCountField = true;
					}
				} else {
					this.countIndex = countIndexs.get(0);
				}
				// System.out.println("unparse sql=" + ServerRouter.genSQL(ast, this.execSql));
				this.parsed = true;
				tbData = new TableData(this);
			}
		} catch (Exception e) {
			this.parsed = false;
			throw new SQLNonTransientException(e);
		} finally {
			if (this.ast == null || this.visitor == null) {
				this.ast = null;
				this.visitor = null;
			}
		}
	}

	// select /*comjoin(1,10000) limit(10)*/ val,count(0) c from tb2 group by val;

	public void parseParams(String sql) {
		int i = 0;
		for (; i < sql.length(); ++i) {
			switch (sql.charAt(i)) {
			case ' ':
			case '\t':
			case '\r':
			case '\n':
				continue;
			}
			break;
		}
		if (!sql.startsWith(COM_GROUP_PREFIX, i)) {
			return;
		}
		parsed = true;
		i += COM_GROUP_PREFIX.length();
		int st = -1;
		int end = i;
		for (; i < sql.length(); ++i) {
			char c = sql.charAt(i);
			switch (c) {
			case ' ':
			case '\t':
			case '\r':
			case '\n':
				continue;
			case ',':
				i++;
				break;
			case ')':
				i++;
				break;
			default:
				if (c >= '0' && c <= '9') {
					if (st == -1)
						st = i;
					end = i + 1;
					continue;
				}
			}
			break;
		}
		if (st >= 0) {
			this.countIndex = Integer.parseInt(sql.substring(st, end));
			// limits
			st = -1;
			end = i;
			for (; i < sql.length(); ++i) {
				char c = sql.charAt(i);
				switch (c) {
				case ' ':
				case '\t':
				case '\r':
				case '\n':
					continue;
				case ')':
					i++;
					break;
				default:
					if (c >= '0' && c <= '9') {
						if (st == -1)
							st = i;
						end = i + 1;
						continue;
					}
				}
				break;
			}
			if (st >= 0) {
				comLimits = Integer.parseInt(sql.substring(st, end));
			}
		}
		for (; i < sql.length(); ++i) {
			switch (sql.charAt(i)) {
			case ' ':
			case ',':
			case '\t':
			case '\r':
			case '\n':
				continue;
			}
			break;
		}
		if (sql.startsWith(COM_LIMIT_PREFIX, i)) {
			i += COM_LIMIT_PREFIX.length();
			st = -1;
			end = i;
			for (; i < sql.length(); ++i) {
				char c = sql.charAt(i);
				switch (c) {
				case ' ':
				case '\t':
				case '\r':
				case '\n':
					continue;
				case ',':
					i++;
					break;
				case ')':
					break;
				default:
					if (c >= '0' && c <= '9') {
						if (st == -1)
							st = i;
						end = i + 1;
						continue;
					}
				}
				break;
			}
			if (st >= 0) {
				this.returnStartIndex = Integer.parseInt(sql.substring(st, end));
				// limits
				st = -1;
				end = i;
				for (; i < sql.length(); ++i) {
					char c = sql.charAt(i);
					switch (c) {
					case ' ':
					case '\t':
					case '\r':
					case '\n':
						continue;
					case ')':
						break;
					default:
						if (c >= '0' && c <= '9') {
							if (st == -1)
								st = i;
							end = i + 1;
							continue;
						}
					}
					break;
				}
				if (st >= 0) {
					this.returnEndIndex = Integer.parseInt(sql.substring(st, end));
				} else {
					this.returnEndIndex = this.returnStartIndex;
					this.returnStartIndex = 0;
				}
			}
		}
		for (; i < sql.length(); ++i) {
			char c = sql.charAt(i);
			if (c == '*' && sql.charAt(i + 1) == '/') {
				this.execSql = sql.substring(i + 2);
				break;
			}
		}
	}

	private void parseAst() throws SQLNonTransientException {
		// 检查当前使用的DB
		String db = sc.getSchema();
		if (db == null) {
			sc.writeErrMessage(ErrorCode.ER_NO_DB_ERROR, "No database selected");
			throw new IllegalArgumentException("No database selected");
		}
		SchemaConfig schema = CobarServer.getInstance().getConfig().getSchemas().get(db);
		if (schema == null) {
			sc.writeErrMessage(ErrorCode.ER_BAD_DB_ERROR, "Unknown database '" + db + "'");
			throw new IllegalArgumentException("Unknown database '" + db + "'");
		}
		// 生成和展开AST
		ast = SQLParserDelegate.parse(this.execSql,
				sc.getCharset() == null ? MySQLParser.DEFAULT_CHARSET : sc.getCharset());
		visitor = new PartitionKeyVisitor(schema);
		visitor.setTrimSchema(schema.isKeepSqlSchema() ? schema.getName() : null);
		ast.accept(visitor);
	}

	// 读取数据，加工合并回写客户端
	public void readRedirect(MultiNodeExecutor multiNodeExecutor, ServerConnection sc, RouteResultsetNode rrn,
			Channel c, boolean autocommit, BlockingSession ss, int flag, BinaryPacket bin) throws IOException {
		if (readHead(multiNodeExecutor, sc, rrn, c, autocommit, ss, flag, bin)) {
			handleRowData(multiNodeExecutor, rrn, c, ss);
		}
	}

	public boolean readHead(MultiNodeExecutor multiNodeExecutor, ServerConnection sc, RouteResultsetNode rrn,
			Channel c, boolean autocommit, BlockingSession ss, int flag, BinaryPacket bin) throws IOException {
		final ReentrantLock lock = multiNodeExecutor.lock;
		lock.lock();
		try {
			if (multiNodeExecutor.isFail.get() == true) {
				return false;
			}
			switch (bin.data[0]) {
			case ErrorPacket.FIELD_COUNT:
				c.setRunning(false);
				multiNodeExecutor.handleFailure(ss, rrn, new BinaryErrInfo((MySQLChannel) c, bin, sc, rrn));
				break;
			case OkPacket.FIELD_COUNT:
				OkPacket ok = new OkPacket();
				ok.read(bin);
				multiNodeExecutor.affectedRows += ok.affectedRows;
				// set lastInsertId
				if (ok.insertId > 0) {
					multiNodeExecutor.insertId = (multiNodeExecutor.insertId == 0) ? ok.insertId : Math.min(
							multiNodeExecutor.insertId, ok.insertId);
				}
				c.setRunning(false);
				multiNodeExecutor.handleSuccessOK(ss, rrn, autocommit, ok);
				break;
			default: // HEADER|FIELDS|FIELD_EOF|ROWS|LAST_EOF
				final MySQLChannel mc = (MySQLChannel) c;
				if (multiNodeExecutor.fieldEOF) {
					for (;;) {
						bin = mc.receive();
						switch (bin.data[0]) {
						case ErrorPacket.FIELD_COUNT:
							c.setRunning(false);
							multiNodeExecutor.handleFailure(ss, rrn, new BinaryErrInfo(mc, bin, sc, rrn));
							return false;
						case EOFPacket.FIELD_COUNT:
							// handleRowData(multiNodeExecutor, rrn, c, ss);
							return true;
						default:
							continue;
						}
					}
				} else {
					synchronized (multiNodeExecutor) {
						bin.packetId = ++multiNodeExecutor.packetId;// HEADER
					}
					List<BinaryPacket> headerList = new LinkedList<BinaryPacket>();
					if (this.tbData.srcFieldLen == this.tbData.destFieldLen) {
						headerList.add(bin);
					} else {
						switch (bin.data.length) {
						case 2:
							MySQLMessage lenMsg = new MySQLMessage(bin.data);
							int len = lenMsg.readUB2() + 1;
							bin.data[0] = (byte) (len & 0xff);
							bin.data[0] = (byte) ((len >> 8) & 0xff);
							headerList.add(bin);
							break;
						default:
							bin.data[0] = (byte) (bin.data[0] + 1);
							headerList.add(bin);
							break;
						}
					}
					for (;;) {
						bin = mc.receive();
						switch (bin.data[0]) {
						case ErrorPacket.FIELD_COUNT:
							c.setRunning(false);
							multiNodeExecutor.handleFailure(ss, rrn, new BinaryErrInfo(mc, bin, sc, rrn));
							return false;
						case EOFPacket.FIELD_COUNT:
							synchronized (multiNodeExecutor) {
								bin.packetId = ++multiNodeExecutor.packetId;// FIELD_EOF
							}
							// for (MySQLPacket packet : headerList) {
							// multiNodeExecutor.buffer = packet.write(multiNodeExecutor.buffer, sc);
							// }
							headerList.add(bin);// EOF
							this.tbData.setHeader(headerList);
							// multiNodeExecutor.buffer = bin.write(multiNodeExecutor.buffer, sc);
							multiNodeExecutor.fieldEOF = true;
							headerList = null;
							// handleRowData(multiNodeExecutor, rrn, c, ss);
							return true;
						default:
							synchronized (multiNodeExecutor) {
								bin.packetId = ++multiNodeExecutor.packetId;// FIELDS
							}
							switch (flag) {
							case RouteResultset.REWRITE_FIELD:
								StringBuilder fieldName = new StringBuilder();
								fieldName.append("Tables_in_").append(ss.getSource().getSchema());
								FieldPacket field = PacketUtil.getField(bin, fieldName.toString());
								BinaryPacket bp = new BinaryPacket();
								ByteBuffer buffer = ByteBuffer.allocate(field.packetLength);
								field.write(buffer, sc);
								bp.data = buffer.array();
								headerList.add(bp);
								break;
							default:
								headerList.add(bin);
							}
						}
					}
				}
			}
		} finally {
			lock.unlock();
		}
		return false;
	}

	void writeRowData(MultiNodeExecutor multiNodeExecutor, ServerConnection source, BinaryPacket eofBin)
			throws IOException {
		synchronized (multiNodeExecutor) {
			for (BinaryPacket head : this.tbData.headerList) {
				multiNodeExecutor.buffer = head.write(multiNodeExecutor.buffer, source);
			}
			int rowCount = 0;
			byte packetId = this.tbData.headerList.get(this.tbData.headerList.size() - 1).packetId;
			if (this.order == null) {// 减少一次KEY拷贝
				for (String key : this.tbData.cacheData.keySet()) {
					if (this.returnStartIndex != -1 && rowCount < this.returnStartIndex) {
						continue;
					}
					++packetId;
					RowDataPacket row = toRowDataPacket(this.tbData.cacheData.get(key), packetId);
					multiNodeExecutor.buffer = row.write(multiNodeExecutor.buffer, source);
					rowCount++;
					if (rowCount >= this.comLimits || (this.returnEndIndex != -1 && rowCount >= this.returnEndIndex)) {
						break;
					}
				}
			} else {
				// long l = System.nanoTime();
				String[] colls = this.tbData.sort();// 排序数据
				// System.out.println("sort time:" + (System.nanoTime() - l) / 1000000 + "ms");
				for (String key : colls) {
					if (this.returnStartIndex != -1 && rowCount < this.returnStartIndex) {
						continue;
					}
					++packetId;
					RowDataPacket row = toRowDataPacket(this.tbData.cacheData.get(key), packetId);
					multiNodeExecutor.buffer = row.write(multiNodeExecutor.buffer, source);
					rowCount++;
					if (rowCount >= this.comLimits || (this.returnEndIndex != -1 && rowCount >= this.returnEndIndex)) {
						break;
					}
				}
				// System.out.println("output time:" + (System.nanoTime() - l) / 1000000 + "ms");
			}
			eofBin.packetId = ++packetId;
			source.write(eofBin.write(multiNodeExecutor.buffer, source));// 输出到客户端
		}
	}

	public RowDataPacket toRowDataPacket(CacheRow crow, byte packetId) {
		RowDataPacket row = new RowDataPacket(crow.colVals.length);
		row.packetId = packetId;
		for (int i = 0; i < crow.colVals.length; i++) {
			row.fieldValues[i] = toByte(crow.colVals[i], i);
		}
		row.packetLength = row.calcPacketSize();
		return row;
	}

	// 根据类型转换为byte数组
	private byte[] toByte(Object object, int index) {
		switch (this.tbData.fieldPacket[index].type) {
		case MysqlFieldType.FIELD_TYPE_LONGLONG: // 8:bigint
		case MysqlFieldType.FIELD_TYPE_STRING:// 254:BINARY CHAR ENUM SET
			return object.toString().getBytes();
		case MysqlFieldType.FIELD_TYPE_BIT: // 16:bit
		case MysqlFieldType.FIELD_TYPE_TINY: {// 1:BOOL BOOLEN TINYINT
			if (object.getClass().equals(Byte.class)) {
				return new byte[] { (Byte) object };
			} else {
				return new byte[] { Byte.parseByte(object.toString()) };
			}
		}
		// 252:BLOB LONGBLOB LONGTEXT MEDIUMBOLOB MEDIUMTEXT TEXT TINYBOLB TINYTEXT
		case MysqlFieldType.FIELD_TYPE_BLOB:
		case MysqlFieldType.FIELD_TYPE_VAR_STRING: {// 253:VARBINARY VARCHAR
			String val = object.toString();
			if (this.tbData.fieldCs[index] == null) {
				return object.toString().getBytes();
			} else {
				try {
					return val.getBytes(this.tbData.fieldCs[index]);
				} catch (Exception e) {
					return val.getBytes();
				}
			}
		}
		case MysqlFieldType.FIELD_TYPE_DATETIME: // 12:DATETIME
		case MysqlFieldType.FIELD_TYPE_TIMESTAMP: {// 7：TIMESTAMP
			Date val = (Date) object;
			return sdf.format(val).getBytes();
		}
		case MysqlFieldType.FIELD_TYPE_NEWDATE:// 14
		case MysqlFieldType.FIELD_TYPE_DATE: {// 10：DATE
			Date val = (Date) object;
			return df.format(val).getBytes();
		}
		case MysqlFieldType.FIELD_TYPE_TIME: {// 11：TIME
			Date val = (Date) object;
			return tf.format(val).getBytes();
		}
		case MysqlFieldType.FIELD_TYPE_DECIMAL: // DECIMAL
		case MysqlFieldType.FIELD_TYPE_NEWDECIMAL: // 246:DECIMAL NUMERIC
		case MysqlFieldType.FIELD_TYPE_DOUBLE: // 5:DOUBLE REAL
		case MysqlFieldType.FIELD_TYPE_FLOAT: {// 4:FLOAT
			Double d = (Double) object;
			int sc = 2;
			if (this.tbData.fieldPacket[index].decimals > 0) {
				sc = this.tbData.fieldPacket[index].decimals;
				sc = sc > 14 ? 14 : sc;
			}
			return dcdf[sc].format(d).getBytes();
		}
		case MysqlFieldType.FIELD_TYPE_LONG: // 3:INT
		case MysqlFieldType.FIELD_TYPE_INT24: // 9 :MEDIUMINT
		case MysqlFieldType.FIELD_TYPE_SHORT: // 2:SMALLINT
		case MysqlFieldType.FIELD_TYPE_YEAR: {// 13:YEAR
			return object.toString().getBytes();
		}
		case MysqlFieldType.FIELD_TYPE_NULL:// 6
		case MysqlFieldType.FIELD_TYPE_VARCHAR:// 15
		case MysqlFieldType.FIELD_TYPE_ENUM:// 247
		case MysqlFieldType.FIELD_TYPE_SET:// 248
		case MysqlFieldType.FIELD_TYPE_TINY_BLOB:// 249
		case MysqlFieldType.FIELD_TYPE_MEDIUM_BLOB:// 250
		case MysqlFieldType.FIELD_TYPE_LONG_BLOB:// 251
		case MysqlFieldType.FIELD_TYPE_GEOMETRY:// 255
		default:
			return object.toString().getBytes();
		}
	}

	/**
	 * 处理RowData数据
	 */
	protected void handleRowData(MultiNodeExecutor multiNodeExecutor, final RouteResultsetNode rrn, Channel c,
			BlockingSession ss) throws IOException {
		final ServerConnection source = ss.getSource();
		BinaryPacket bin = null;
		int rowCount = 0;
		int size = 0;
		for (;;) {
			bin = ((MySQLChannel) c).receive();
			switch (bin.data[0]) {
			case ErrorPacket.FIELD_COUNT:
				c.setRunning(false);
				synchronized (multiNodeExecutor) {
					multiNodeExecutor.handleFailure(ss, rrn, new BinaryErrInfo(((MySQLChannel) c), bin, source, rrn));
				}
				return;
			case EOFPacket.FIELD_COUNT:
				c.setRunning(false);
				if (source.isAutocommit()) {
					c = ss.getTarget().remove(rrn);
					if (c != null) {
						if (multiNodeExecutor.isFail.get() || source.isClosed()) {
							/**
							 * this {@link Channel} might be closed by other thread in this condition, so that do not
							 * release this channel
							 */
							c.close();
						} else {
							c.release();
						}
					}
				}
				handleSuccessEOF(multiNodeExecutor, ss, bin);
				return;
			default:
				synchronized (multiNodeExecutor) {
					bin.packetId = ++multiNodeExecutor.packetId;// ROWS
				}
				MultiNodeComDataExecutor.this.tbData.addRow(bin);
				rowCount++;
				if (rowCount >= this.comLimits) {
					c.setRunning(false);
					if (source.isAutocommit()) {
						c = ss.getTarget().remove(rrn);
						if (c != null) {
							if (multiNodeExecutor.isFail.get() || source.isClosed()) {
								c.close();
							} else {
								c.release();
							}
						}
					}
					handleSuccessEOF(multiNodeExecutor, ss, bin);
				}
				// multiNodeExecutor.buffer = bin.write(multiNodeExecutor.buffer, source);
				// size += bin.packetLength;
				// if (size > multiNodeExecutor.RECEIVE_CHUNK_SIZE) {
				// handleNext(multiNodeExecutor, rrn, c, ss);
				// return;
				// }
			}
		}
	}

	public void handleSuccessEOF(MultiNodeExecutor multiNodeExecutor, BlockingSession ss, BinaryPacket bin)
			throws IOException {
		final ReentrantLock lock = multiNodeExecutor.lock;
		lock.lock();
		try {
			if (multiNodeExecutor.decrementCountAndIsZero()) {
				if (multiNodeExecutor.isFail.get()) {
					synchronized (multiNodeExecutor) {
						multiNodeExecutor.notifyFailure(ss);
					}
					return;
				}

				ServerConnection source = ss.getSource();
				if (source.isAutocommit()) {
					ss.release();
				}
				synchronized (multiNodeExecutor) {
					bin.packetId = ++multiNodeExecutor.packetId;// LAST_EOF
				}
				writeRowData(multiNodeExecutor, source, bin);
				// source.write(bin.write(multiNodeExecutor.buffer, source));// 输出到客户端
			}
		} finally {
			lock.unlock();
		}

	}

	private String[] sort(List<Pair<Integer, SortOrder>> orderList) {
		String[] keys = this.tbData.cacheData.keySet().toArray(new String[0]);
		SingleOrderComparator c = new SingleOrderComparator(this, orderList);
		Arrays.sort(keys, c);
		return keys;
	}

	public static class SingleOrderComparator implements Comparator<String> {
		final MultiNodeComDataExecutor comExe;
		Pair<Integer, SortOrder>[] orders;

		public SingleOrderComparator(MultiNodeComDataExecutor multiNodeComDataExecutor,
				List<Pair<Integer, SortOrder>> orderList) {
			this.comExe = multiNodeComDataExecutor;
			orders = (Pair<Integer, SortOrder>[]) Array.newInstance(Pair.class, orderList.size());
			orders = (Pair<Integer, SortOrder>[]) orderList.toArray(orders);
		}

		@Override
		public int compare(String o1, String o2) {
			CacheRow r1 = this.comExe.tbData.cacheData.get(o1);
			CacheRow r2 = this.comExe.tbData.cacheData.get(o2);
			// System.out.println("key1=" + o1 + "    key2=" + o2);
			for (Pair<Integer, SortOrder> od : orders) {
				int sindex = od.getKey();
				Object val1 = r1.colVals[sindex];
				Object val2 = r2.colVals[sindex];
				// System.out.println("val1=" + val1 + "    val2=" + val2);
				int res = 0;
				if (val1 instanceof String) {
					String s1 = (String) val1;
					String s2 = (String) val2;
					res = s1.compareTo(s2);
				} else if (val1 instanceof Long) {
					Long s1 = (Long) val1;
					Long s2 = (Long) val2;
					res = s1.compareTo(s2);
				} else if (val1 instanceof Integer) {
					Integer s1 = (Integer) val1;
					Integer s2 = (Integer) val2;
					res = s1.compareTo(s2);
				} else if (val1 instanceof Byte) {
					Byte s1 = (Byte) val1;
					Byte s2 = (Byte) val2;
					res = s1.compareTo(s2);
				} else if (val1 instanceof Date) {
					Date s1 = (Date) val1;
					Date s2 = (Date) val2;
					res = s1.compareTo(s2);
				}
				if (res != 0) {
					if (od.getValue() == SortOrder.ASC) {
						return res;
					} else {
						return -res;
					}
				}
			}
			return 0;
		}
	}

	public static void main(String[] args) {
		String sql = "select /* comjoin( 33 ) */ val,count(0) c from tb2 group by val";
		MultiNodeComDataExecutor com = new MultiNodeComDataExecutor(sql, null);
		long st = System.nanoTime();
		for (int i = 0; i < 10000000; i++) {
			com.parseParams(sql);
			if (i % 1000000 == 0) {
				System.out.println("countIndex=" + com.countIndex + "  comLimits=" + com.comLimits + "  " + i + "   "
						+ (System.nanoTime() - st) / 1000000 + "ms");
			}
		}
		System.out.println("countIndex=" + com.countIndex + "  comLimits=" + com.comLimits + "  " + 1000000 + "   "
				+ (System.nanoTime() - st) / 1000000 + "ms");
		// 10.3s 1000W

		Pattern p = Pattern.compile("(?i)/\\*\\s*comjoin\\(\\s*(\\d+)\\s*,?\\s*(\\d+)?\\s*\\)\\s*\\*/");
		Matcher m = p.matcher(sql);
		if (m.find()) {
			String countIndex = m.group(1);
			String countLimit = m.group(2);
			System.out.println("countIndex=" + countIndex);
			System.out.println("countLimit=" + countLimit);
		}
		st = System.nanoTime();
		for (int i = 0; i < 10000000; i++) {
			m = p.matcher(sql);
			m.find();
			String countIndex = m.group(1);
			String countLimit = m.group(2);
			if (i % 1000000 == 0) {
				System.out.println("countIndex=" + countIndex + "  comLimits=" + countLimit + "  " + i + "   "
						+ (System.nanoTime() - st) / 1000000 + "ms");
			}
		}
		System.out.println("  " + 1000000 + "   " + (System.nanoTime() - st) / 1000000 + "ms");
		// 35.8s 1000W

	}
}
