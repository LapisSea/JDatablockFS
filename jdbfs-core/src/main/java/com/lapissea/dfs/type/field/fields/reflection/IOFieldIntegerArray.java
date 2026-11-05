package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.UnsupportedCodeGenType;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.SpecializedGenerator;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.CollectionAdapter;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

import static com.lapissea.dfs.type.field.StoragePool.IO;

public final class IOFieldIntegerArray<T extends IOInstance<T>, CollectionType> extends NullFlagCompanyField<T, CollectionType> implements SpecializedGenerator{
	
	@SuppressWarnings("unused")
	private static final class UsageList implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			if(!(type instanceof ParameterizedType parmType)) return false;
			if(parmType.getRawType() != List.class && parmType.getRawType() != ArrayList.class) return false;
			var args = parmType.getActualTypeArguments();
			return Integer.class == args[0];
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			var parmType = (ParameterizedType)field.getGenericType(null);
			var args     = parmType.getActualTypeArguments();
			//noinspection unchecked
			return new IOFieldIntegerArray<>(
				field, (Class<Integer>)args[0],
				(Class<CollectionAdapter<Integer, List<Integer>>>)(Class<?>)CollectionAdapter.OfList.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){ return Set.of(IOFieldIntegerArray.class); }
		
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.of(IOValue.class, (field, ann) -> {
					return new BehaviourRes<>(new VirtualFieldDefinition<T, NumberSize>(
						IO, FieldNames.numberSize(field), NumberSize.class
					));
				})
			);
		}
	}
	
	@SuppressWarnings("unused")
	private static final class UsageArray implements FieldUsage{
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			var raw = Utils.typeToRaw(type);
			if(!raw.isArray()) return false;
			return Integer.class == raw.componentType();
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field){
			//noinspection unchecked
			return new IOFieldIntegerArray<>(
				field, (Class<Integer>)field.getType().componentType(),
				(Class<CollectionAdapter<Integer, List<Integer>>>)(Class<?>)CollectionAdapter.OfArray.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){ return Set.of(IOFieldIntegerArray.class); }
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IOValue.class, BehaviourSupport::collectionLength),
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.of(IOValue.class, (field, ann) -> {
					return new BehaviourRes<>(new VirtualFieldDefinition<T, NumberSize>(
						IO, FieldNames.numberSize(field), NumberSize.class
					));
				})
			);
		}
	}
	
	private record IntIO(Class<Integer> type, NumberSize size) implements CollectionAdapter.ElementIOImpl<Integer>{
		@Override
		public Class<Integer> componentType(){
			return type;
		}
		@Override
		public long calcByteSize(DataProvider provider, Integer element){
			throw new UnsupportedOperationException();
		}
		@Override
		public OptionalLong getFixedByteSize(){
			return size.optionalBytesLong;
		}
		@Override
		public void write(DataProvider provider, ContentWriter dest, Integer element) throws IOException{
			size.writeIntSigned(dest, element);
		}
		@Override
		public Integer read(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			return size.readIntSigned(src);
		}
		@Override
		public void skip(DataProvider provider, ContentReader src, GenericContext genericContext) throws IOException{
			size.skip(src);
		}
	}
	
	private       IOFieldPrimitive.FInt<T>                                        collectionSize;
	private       IOField<T, NumberSize>                                          numSize;
	private final EnumMap<NumberSize, CollectionAdapter<Integer, CollectionType>> addapters;
	
	public IOFieldIntegerArray(FieldAccessor<T> accessor, Class<Integer> type, Class<CollectionAdapter<Integer, CollectionType>> addapter){
		super(accessor);
		
		this.addapters = new EnumMap<>(NumberSize.class);
		for(var siz : NumberSize.FLAG_INFO){
			if(siz.greaterThan(NumberSize.INT)) continue;
			addapters.put(siz, CollectionAdapter.newAdapter(addapter, new IntIO(type, siz)));
		}
		
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, inst) -> {
			var siz = getColSize(ioPool, inst);
			if(siz == 0) return 0;
			var col = get(ioPool, inst);
			if(col == null) return 0;
			var nSiz = getNumSize(ioPool, inst);
			return addapters.get(nSiz).getSize(col)*(long)nSiz.bytes;
		}));
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.HAS_NO_POINTERS);
	}
	
	private NumberSize getNumSize(VarPool<T> pool, T inst){
		return numSize.get(pool, inst);
	}
	
	private int getColSize(VarPool<T> pool, T inst){
		return collectionSize.getValue(pool, inst);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		collectionSize = fields.requireExactInt(FieldNames.collectionLen(getAccessor()));
		numSize = fields.requireExact(NumberSize.class, FieldNames.numberSize(getAccessor()));
	}
	
	@Override
	public CollectionType get(VarPool<T> ioPool, T instance){
		return getNullable(ioPool, instance, () -> addapters.get(NumberSize.BYTE).makeNew(0));
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return Utils.concat(super.getGenerators(), new ValueGeneratorInfo<>(numSize, new ValueGenerator<>(){
			@Override
			public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
				if(numSize.isNull(ioPool, instance)){
					return true;
				}
				var col       = get(ioPool, instance);
				var nSiz      = calcNumSize(col);
				var actualSiz = getNumSize(ioPool, instance);
				return nSiz != actualSiz;
			}
			@Override
			public NumberSize generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod){
				var arr = get(ioPool, instance);
				return calcNumSize(arr);
			}
			
			private NumberSize calcNumSize(CollectionType col){
				if(col == null) return NumberSize.VOID;
				int min = 0, max = 0;
				for(int i : addapters.get(NumberSize.VOID).asListView(col)){
					min = Math.min(min, i);
					max = Math.max(max, i);
				}
				var minSiz = NumberSize.bySizeSigned(min);
				var maxSiz = NumberSize.bySizeSigned(max);
				return minSiz.max(maxSiz);
			}
		}));
	}
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var col = get(ioPool, instance);
		if(col == null) return;
		var nSiz = getNumSize(ioPool, instance);
		addapters.get(nSiz).write(col, provider, dest);
	}
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		CollectionType data;
		if(nullable() && getIsNull(ioPool, instance)) data = null;
		else{
			int size = getColSize(ioPool, instance);
			var nSiz = getNumSize(ioPool, instance);
			data = addapters.get(nSiz).read(size, provider, src, genericContext);
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		int size = getColSize(ioPool, instance);
		if(size == 0) return;
		var nSiz = getNumSize(ioPool, instance);
		src.skipExact(size*(long)nSiz.bytes);
	}
	
	@Override
	public void injectWriteField(CodeBlock body, AccessMap accessMap) throws MalformedJorth{
		injectWriteFieldNullable(body, accessMap, null, writer -> {
			var value = accessMap.temporaryLocalField(getType(), "integers", writer);
			writer.set(value);
			var width = accessMap.temporaryLocalField(NumberSize.class, "integerWidth", writer);
			accessMap.get(numSize, writer);
			writer.set(width);
			var index = accessMap.temporaryLocalField(int.class, "integerIndex", writer);
			writer.set(index, 0);
			writer.loop(check -> {
				check.get(index);
				accessMap.get(collectionSize, check);
				check.lessThanOp();
			}, loop -> {
				loop.get(width).call("writeIntSigned", args -> {
					args.get("dest").get(value);
					if(getType().isArray()) args.get(index).getArrayElement();
					else args.call("get", i -> i.get(index)).cast(Integer.class);
					args.call("intValue");
				});
				loop.inc(index);
			});
		});
	}
	
	@Override
	public void injectSkipField(CodeBlock body, AccessMap accessMap) throws MalformedJorth{
		body.get("src").call("skipExact", args -> {
			accessMap.get(collectionSize, args);
			args.cast(long.class);
			accessMap.get(numSize, args);
			args.get(NumberSize.class, "bytes").cast(long.class).mul();
		});
	}
	
	@Override
	public void injectReadField(CodeBlock body, AccessMap accessMap) throws MalformedJorth, UnsupportedCodeGenType{
		injectReadFieldNullable(body, accessMap, null, bod -> {
			accessMap.markTemporary();
			var size = accessMap.temporaryLocalField(int.class, "collectionSize", bod);
			accessMap.get(collectionSize, bod);
			bod.set(size);
			var width = accessMap.temporaryLocalField(NumberSize.class, "numberSize", bod);
			accessMap.get(numSize, bod);
			bod.set(width);
			boolean array  = getType().isArray();
			var     result = accessMap.temporaryLocalField(array? Integer[].class : ArrayList.class, "integers", bod);
			bod.newObj(array? Integer[].class : ArrayList.class, args -> args.get(size)).set(result);
			var index = accessMap.temporaryLocalField(int.class, "index", bod);
			bod.set(index, 0);
			bod.loop(check -> check.get(index).get(size).lessThanOp(), loop -> {
				loop.get(result);
				if(array){
					loop.get(index).get(width).call("readIntSigned", args -> args.get("src")).box().setArrayElement();
				}else{
					loop.call("add", args -> args.get(width).call("readIntSigned", a -> a.get("src")).box()).pop();
				}
				loop.inc(index);
			});
			bod.get(result).cast(getType());
			accessMap.dropTemporary(bod);
		});
	}
}
