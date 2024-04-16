package com.lapissea.dfs.objects;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.IterablePPs;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.List;
import java.util.function.Function;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public sealed interface CollectionInfo{
	
	enum Layout{
		DYNAMIC,
		STRIPED,
		STRUCT_OF_ARRAYS,
	}
	
	interface Store extends IOInstance.Def<Store>{
		StructPipe<Store> PIPE = StandardStructPipe.of(Store.class);
		CollectionInfo val();
		static Store of(CollectionInfo val){
			class Cache{
				private static final Function<CollectionInfo, Store> make = Def.constrRef(Store.class, CollectionInfo.class);
			}
			return Cache.make.apply(val);
		}
	}
	
	@IOValue
	final class NullValue extends IOInstance.Managed<NullValue> implements CollectionInfo{
		
		static final NullValue INSTANCE = new NullValue();
		
		@Override
		public Class<?> constantType(){ return null; }
		@Override
		public int length(){ return 0; }
		@Override
		public Layout layout(){ return Layout.STRIPED; }
		@Override
		public boolean hasNulls(){ return false; }
		@Override
		public IterablePP<?> iter(Object collection){ return IterablePPs.of(); }
	}
	
	final class PrimitiveArrayInfo extends IOInstance.Managed<PrimitiveArrayInfo> implements CollectionInfo{
		
		@IOValue
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private int length;
		
		private Class<?> elementType;
		
		public PrimitiveArrayInfo(){ }
		public PrimitiveArrayInfo(int length, Class<?> elementType){
			this.length = length;
			this.elementType = elementType;
		}
		
		@IOValue
		private void elementType(){ elementType = null; }
		
		@Override
		public Class<?> constantType(){ return elementType; }
		@Override
		public int length(){ return length; }
		@Override
		public Layout layout(){ return Layout.STRUCT_OF_ARRAYS; }
		@Override
		public boolean hasNulls(){ return false; }
		
		public static final StructPipe<PrimitiveArrayInfo> PIPE = StandardStructPipe.of(PrimitiveArrayInfo.class);
		
		@Override
		public IterablePP<?> iter(Object collection){
			return IterablePPs.rangeMap(0, Array.getLength(collection), (int i) -> Array.get(collection, i));
		}
	}
	
	@IOValue
	final class ArrayInfo extends IOInstance.Managed<ArrayInfo> implements CollectionInfo{
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private int      length;
		@IONullability(NULLABLE)
		private Class<?> constantType;
		private Layout   layout;
		private boolean  hasNulls;
		
		private Class<?> arrayType;
		
		public ArrayInfo(){ }
		public ArrayInfo(Class<?> arrayType, int length, Class<?> constantType, Layout layout, boolean hasNulls){
			this.length = length;
			this.constantType = constantType;
			this.layout = layout;
			this.hasNulls = hasNulls;
			this.arrayType = arrayType;
		}
		
		@Override
		public Class<?> constantType(){ return constantType; }
		@Override
		public int length(){ return length; }
		@Override
		public Layout layout(){ return layout; }
		@Override
		public boolean hasNulls(){ return hasNulls; }
		
		public Class<?> getArrayType(){ return arrayType; }
		
		@Override
		public IterablePP<?> iter(Object collection){
			var arr = (Object[])collection;
			return IterablePPs.rangeMap(0, arr.length, (int i) -> arr[i]);
		}
	}
	
	@IOValue
	final class ListInfo extends IOInstance.Managed<ListInfo> implements CollectionInfo{
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private int      length;
		@IONullability(NULLABLE)
		private Class<?> constantType;
		private Layout   layout;
		private boolean  hasNulls;
		
		private boolean unmodifiable;
		
		public ListInfo(){ }
		public ListInfo(int length, Class<?> constantType, Layout layout, boolean hasNulls, boolean unmodifiable){
			this.length = length;
			this.constantType = constantType;
			this.layout = layout;
			this.hasNulls = hasNulls;
			this.unmodifiable = unmodifiable;
		}
		
		@Override
		public Class<?> constantType(){ return constantType; }
		@Override
		public int length(){ return length; }
		@Override
		public Layout layout(){ return layout; }
		@Override
		public boolean hasNulls(){ return hasNulls; }
		
		public boolean isUnmodifiable(){ return unmodifiable; }
		
		@Override
		public IterablePP<?> iter(Object collection){
			//noinspection unchecked
			var list = (List<Object>)collection;
			return list::iterator;
		}
	}
	
	Class<?> constantType();
	int length();
	Layout layout();
	boolean hasNulls();
	
	IterablePP<?> iter(Object collection);
	
	default long calcIOBytes(DataProvider provider){
		return Store.PIPE.calcUnknownSize(provider, Store.of(this), WordSpace.BYTE);
	}
	
	default void write(DataProvider provider, ContentWriter dest) throws IOException{
		Store.PIPE.write(provider, dest, Store.of(this));
	}
	static CollectionInfo read(DataProvider provider, ContentReader src) throws IOException{
		return Store.PIPE.readNew(provider, src, null).val();
	}
}
