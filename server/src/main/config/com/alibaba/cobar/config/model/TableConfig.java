/*
 * Copyright 1999-2012 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * (created at 2012-6-13)
 */
package com.alibaba.cobar.config.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.alibaba.cobar.config.model.rule.RuleConfig;
import com.alibaba.cobar.config.model.rule.TableRuleConfig;
import com.alibaba.cobar.config.util.ConfigException;
import com.alibaba.cobar.util.SplitUtil;

/**
 * @author <a href="mailto:shuo.qius@alibaba-inc.com">QIU Shuo</a>
 */
public class TableConfig {
	private final String name;
	private final String[] dataNodes;
	private final TableRuleConfig rule;
	private final Set<String> columnIndex;
	private final boolean ruleRequired;
	private final boolean comData;
	private final String groupType;

	private final int tableGroupType;
	private final Pattern tablePattern;

	public TableConfig(String name, String dataNode, TableRuleConfig rule, boolean ruleRequired, String groupType,
			boolean comData) {
		if (name == null) {
			throw new IllegalArgumentException("table name is null");
		}
		this.name = name.toUpperCase();
		this.dataNodes = SplitUtil.split(dataNode, ',', '$', '-', '[', ']');
		if (this.dataNodes == null || this.dataNodes.length <= 0) {
			throw new IllegalArgumentException("invalid table dataNodes: " + dataNode);
		}
		this.rule = rule;
		this.columnIndex = buildColumnIndex(rule);
		this.ruleRequired = ruleRequired;
		this.comData = comData;
		this.groupType = groupType;
		if (this.groupType == null || this.groupType.trim().equals("")) {
			tableGroupType = 0;
			this.tablePattern = null;
		} else {
			try {
				if (this.groupType.toLowerCase().equals("regex")) {
					this.tablePattern = Pattern.compile(this.name);
					tableGroupType = 1;// regex
					// } else if (this.groupType.toLowerCase().equals("expr")) {
					// this.tablePattern = Pattern.compile(this.name);
					// tableGroupType = 2;// expr
				} else {
					throw new ConfigException("Unsupported table groupType");
				}
			} catch (Exception e) {
				throw new ConfigException(e);
			}
		}
	}

	public boolean existsColumn(String columnNameUp) {
		return columnIndex.contains(columnNameUp);
	}

	/**
	 * @return upper-case
	 */
	public String getName() {
		return name;
	}

	public String[] getDataNodes() {
		return dataNodes;
	}

	public boolean isRuleRequired() {
		return ruleRequired;
	}

	public TableRuleConfig getRule() {
		return rule;
	}

	private static Set<String> buildColumnIndex(TableRuleConfig rule) {
		if (rule == null) {
			return Collections.emptySet();
		}
		List<RuleConfig> rs = rule.getRules();
		if (rs == null || rs.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> columnIndex = new HashSet<String>();
		for (RuleConfig r : rs) {
			List<String> columns = r.getColumns();
			if (columns != null) {
				for (String col : columns) {
					if (col != null) {
						columnIndex.add(col.toUpperCase());
					}
				}
			}
		}
		return columnIndex;
	}

	public boolean isComData() {
		return comData;
	}

	public int getTableGroupType() {
		return tableGroupType;
	}

	public Pattern getTablePattern() {
		return tablePattern;
	}
}
