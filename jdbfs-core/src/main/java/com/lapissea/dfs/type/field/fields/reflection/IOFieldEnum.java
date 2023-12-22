package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.bit.BitReader;
import com.lapissea.dfs.io.bit.BitWriter;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.fields.BitField;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public final class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends BitField<T, E>{
	
	@SuppressWarnings({"unused", "rawtypes"})
	private static final class Usage extends FieldUsage.InstanceOf<Enum>{
		public Usage(){ super(Enum.class, Set.of(IOFieldEnum.class)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, Enum> create(FieldAccessor<T> field){
			return new IOFieldEnum<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(Behaviour.of(IONullability.class, BehaviourSupport::ioNullability));
		}
	}
	
	private final EnumUniverse<E> enumUniverse;
	private final Supplier<E>     createDefaultIfNull;
	
	public IOFieldEnum(FieldAccessor<T> field){
		super(field);
		
		enumUniverse = EnumUniverse.ofUnknown(field.getType());
		initSizeDescriptor(SizeDescriptor.Fixed.of(WordSpace.BIT, enumUniverse.getBitSize(nullable())));
		
		if(getNullability() == DEFAULT_IF_NULL && enumUniverse.isEmpty()){
			throw new MalformedStruct(DEFAULT_IF_NULL + " is not supported for empty enums");
		}
		createDefaultIfNull = () -> enumUniverse.getFirst();
	}
	
	@Override
	public E get(VarPool<T> ioPool, T instance){
		return getNullable(ioPool, instance, createDefaultIfNull);
	}
	@Override
	public boolean isNull(VarPool<T> ioPool, T instance){
		return getNullability() != DEFAULT_IF_NULL && rawGet(ioPool, instance) == null;
	}
	
	@Override
	public void set(VarPool<T> ioPool, T instance, E value){
		super.set(ioPool, instance, switch(getNullability()){
			case NULLABLE, DEFAULT_IF_NULL -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	@Override
	public void writeBits(VarPool<T> ioPool, BitWriter<?> dest, T instance) throws IOException{
		dest.writeEnum(enumUniverse, get(ioPool, instance), nullable());
	}
	
	@Override
	public void readBits(VarPool<T> ioPool, BitReader src, T instance) throws IOException{
		set(ioPool, instance, src.readEnum(enumUniverse, nullable()));
	}
	
	@Override
	public void skipReadBits(BitReader src, T instance) throws IOException{
		src.skipEnum(enumUniverse, nullable());
	}
	
	@Override
	public boolean instancesEqual(VarPool<T> ioPool1, T inst1, VarPool<T> ioPool2, T inst2){
		return get(ioPool1, inst1) == get(ioPool2, inst2);
	}
}
