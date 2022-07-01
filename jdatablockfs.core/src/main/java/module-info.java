module JDatablockFS.core {
	requires jdk.unsupported;
	
	requires jlapisutil;
	requires RoaringBitmap;
	requires Jorth;
	requires generics.resolver;
	
	exports com.lapissea.cfs.type.field.annotations;
	exports com.lapissea.cfs.objects;
	
	exports com.lapissea.cfs;
	exports com.lapissea.cfs.chunk;
	exports com.lapissea.cfs.io;
	exports com.lapissea.cfs.io.content;
	exports com.lapissea.cfs.io.bit;
	exports com.lapissea.cfs.io.impl;
	exports com.lapissea.cfs.io.instancepipe;
	exports com.lapissea.cfs.objects.collections;
	exports com.lapissea.cfs.objects.text;
	exports com.lapissea.cfs.type;
	exports com.lapissea.cfs.type.field;
	exports com.lapissea.cfs.exceptions;
	
	exports com.lapissea.cfs.type.field.access to JDatablockFS.tools, jlapisutil;
	
	exports com.lapissea.cfs.type.field.fields.reflection to JDatablockFS.tools, jlapisutil;
}