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
package com.alibaba.cobar.util;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author xianmao.hexm
 */
public class NameableExecutor extends ThreadPoolExecutor {

	protected String name;

	public NameableExecutor(String name, int size, BlockingQueue<Runnable> queue, ThreadFactory factory) {
		super(size, size * 2, Long.MAX_VALUE, TimeUnit.NANOSECONDS, queue, factory);
		this.name = name;
	}

	public String getName() {
		return name;
	}

}
