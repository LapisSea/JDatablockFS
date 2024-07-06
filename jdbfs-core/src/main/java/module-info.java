module JDatablockFS.core {
	requires jdk.unsupported;
	
	requires jlapisutil;
	requires roaringbitmap;
	requires Jorth;
	requires generics.resolver;
	requires org.lz4.java;
	
	exports com.lapissea.dfs.type.field.annotations;
	exports com.lapissea.dfs.objects;
	
	exports com.lapissea.dfs;
	exports com.lapissea.dfs.config;
	exports com.lapissea.dfs.core;
	exports com.lapissea.dfs.core.chunk;
	exports com.lapissea.dfs.io;
	exports com.lapissea.dfs.io.content;
	exports com.lapissea.dfs.io.bit;
	exports com.lapissea.dfs.io.impl;
	exports com.lapissea.dfs.io.instancepipe;
	exports com.lapissea.dfs.objects.collections;
	exports com.lapissea.dfs.objects.text;
	exports com.lapissea.dfs.type;
	exports com.lapissea.dfs.type.field;
	exports com.lapissea.dfs.type.field.fields;
	exports com.lapissea.dfs.exceptions;
	exports com.lapissea.dfs.query;
	exports com.lapissea.dfs.utils;
	exports com.lapissea.dfs.utils.function;
	exports com.lapissea.dfs.utils.iterableplus;
	
	exports com.lapissea.dfs.type.field.access to JDatablockFS.tools, JDatablockFS.run, jlapisutil;
	exports com.lapissea.dfs.type.compilation to jlapisutil, JDatablockFS.run;
	opens com.lapissea.dfs.type.compilation to jlapisutil, JDatablockFS.run;
	
	exports com.lapissea.dfs.type.field.fields.reflection to JDatablockFS.tools, jlapisutil;
	exports com.lapissea.dfs.logging to JDatablockFS.tools, JDatablockFS.run;
	exports com.lapissea.dfs.type.field.fields.reflection.wrappers to JDatablockFS.tools, jlapisutil;
}
