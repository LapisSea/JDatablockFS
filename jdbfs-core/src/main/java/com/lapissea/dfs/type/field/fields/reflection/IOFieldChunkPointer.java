package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

import static com.lapissea.dfs.objects.NumberSize.LARGEST;
import static com.lapissea.dfs.objects.NumberSize.VOID;

public final class IOFieldChunkPointer<T extends IOInstance<T>> extends IOField<T, ChunkPointer> implements IOField.SpecializedGenerator{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<ChunkPointer>{
		public Usage(){ super(ChunkPointer.class, Set.of(IOFieldChunkPointer.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, ChunkPointer> create(FieldAccessor<T> field){
			return new IOFieldChunkPointer<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.justDeps(IODependency.NumSize.class, a -> Set.of(a.value())),
				Behaviour.of(IODependency.VirtualNumSize.class, BehaviourSupport::virtualNumSize)
			);
		}
	}
	
	private record DynamicFieldSize<T extends IOInstance<T>>(IOField<T, NumberSize> field) implements BiFunction<VarPool<T>, T, NumberSize>{
		@Override
		public NumberSize apply(VarPool<T> ioPool, T instance){
			return field.get(ioPool, instance);
		}
	}
	
	private final boolean             forceFixed;
	private final VaryingSize         maxSize;
	private       DynamicFieldSize<T> dynamicSize;
	
	public IOFieldChunkPointer(FieldAccessor<T> accessor){
		this(accessor, null);
	}
	private IOFieldChunkPointer(FieldAccessor<T> accessor, VaryingSize maxSize){
		super(accessor);
		this.forceFixed = maxSize != null;
		this.maxSize = maxSize == null? VaryingSize.MAX : maxSize;
	}
	
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of();
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		
		if(!forceFixed && IOFieldTools.getDynamicSize(getAccessor()) instanceof Some(var field)){
			dynamicSize = new DynamicFieldSize<>(field);
			initSizeDescriptor(SizeDescriptor.Unknown.of(VOID, Optional.of(LARGEST), field.getAccessor()));
		}else{
			initSizeDescriptor(SizeDescriptor.Fixed.of(maxSize.size.bytes));
		}
	}
	@Override
	public IOField<T, ChunkPointer> maxAsFixedSize(VaryingSize.Provider varProvider){
		var    ptr = getType() == ChunkPointer.class;
		String uid = sizeDescriptorSafe() instanceof SizeDescriptor.UnknownNum<T> num? num.getAccessor().getName() : null;
		return new IOFieldChunkPointer<>(getAccessor(), varProvider.provide(LARGEST, uid, ptr));
	}
	
	private NumberSize getSize(VarPool<T> ioPool, T instance){
		if(dynamicSize != null) return dynamicSize.apply(ioPool, instance);
		return maxSize.size;
	}
	private NumberSize getSafeSize(VarPool<T> ioPool, T instance, long num){
		if(dynamicSize != null) return dynamicSize.apply(ioPool, instance);
		return maxSize.safeNumber(num);
	}
	
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{
		var oVal = get(ioPool, instance);
		var val  = oVal == null? 0 : oVal.getValue();
		
		var size = getSafeSize(ioPool, instance, val);
		size.write(dest, val);
	}
	
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size = getSize(ioPool, instance);
		set(ioPool, instance, ChunkPointer.of(size.read(src)));
	}
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		var size = getSize(ioPool, instance);
		size.skip(src);
	}
	
	@Override
	public void injectReadField(CodeStream writer, AccessMap accessMap) throws MalformedJorth, AccessMap.ConstantNeeded{
		accessMap.preSet(getAccessor(), writer);
		writer.write("static call {} of start", ChunkPointer.class);
		if(dynamicSize == null){
			maxSize.size.readConst(writer, "get #arg src", false);
		}else{
			accessMap.get(dynamicSize.field.getAccessor(), writer);
			NumberSize.readDyn(writer, "get #arg src", false);
		}
		writer.wEnd();
		accessMap.set(getAccessor(), writer);
	}
}
