package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.bit.BitReader;
import com.lapissea.dfs.io.bit.BitUtils;
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
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public final class IOFieldEnum<T extends IOInstance<T>, E extends Enum<E>> extends BitField<T, E> implements IOField.SpecializedGenerator{
	
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
	private final E               defaultValue;
	
	public IOFieldEnum(FieldAccessor<T> field){
		super(field);
		
		enumUniverse = EnumUniverse.ofUnknown(field.getType());
		initSizeDescriptor(SizeDescriptor.Fixed.of(WordSpace.BIT, enumUniverse.getBitSize(nullable())));
		
		if(getNullability() == DEFAULT_IF_NULL && enumUniverse.isEmpty()){
			throw new MalformedStruct(DEFAULT_IF_NULL + " is not supported for empty enums");
		}
		defaultValue = enumUniverse.getFirst();
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.PRIMITIVE_OR_ENUM);
	}
	
	@Override
	public E get(VarPool<T> ioPool, T instance){
		return getNullable(ioPool, instance, defaultValue);
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
	
	public static <E extends Enum<E>> E rawToEnum(int rawInt, int bits, Class<E> enumType) throws IOException{
		var bytes     = BitUtils.bitsToBytes(bits);
		var oneBits   = bytes*8 - bits;
		var oneMask   = (int)BitUtils.makeMask(oneBits);
		var valueMask = (int)BitUtils.makeMask(bits);
		
		var rawOnes = rawInt >>> bits;
		if(rawOnes != oneMask){
			throw new IOException("Illegal one bits");
		}
		var index = rawInt&valueMask;
		return EnumUniverse.of(enumType).get(index);
	}
	
	@Override
	public void injectReadField(CodeStream writer, AccessMap accessMap) throws MalformedJorth{
		if(nullable()) throw new NotImplementedException("Nullable enum not implemented yet");
		
		var bits  = enumUniverse.getBitSize(nullable());
		var bytes = BitUtils.bitsToBytes(bits);
		var readInt = switch(bytes){
			case 0, 1, 2, 3 -> "call readUnsignedInt" + bytes;
			case 4 -> "call readUnsignedInt4 cast int";
			default -> throw new UnsupportedOperationException();
		};
		
		var oneBits   = bytes*8 - bits;
		var oneMask   = BitUtils.makeMask(oneBits);
		var valueMask = BitUtils.makeMask(bits);
		writer.write("dup");
		accessMap.preSet(getAccessor(), writer);
		writer.write(
			"""
				static call com.lapissea.dfs.type.field.fields.reflection.IOFieldEnum rawToEnum start
					get #arg src
					{}
					{}
					class {}
				end
				cast {2}
				""", readInt, bits, enumUniverse.type
		);
		accessMap.set(getAccessor(), writer);
	}
}
