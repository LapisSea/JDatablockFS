package com.lapissea.dfs.type.field.fields.reflection.wrappers;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.LimitedContentReader;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.text.Encoding;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.StoragePool;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.dfs.type.string.StringifySettings;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class IOFieldFusedString<CTyp extends IOInstance<CTyp>> extends IOField<CTyp, String>{
	
	@SuppressWarnings("unused")
	private static final class Usage extends FieldUsage.InstanceOf<String>{
		public Usage(){ super(String.class, Set.of(IOFieldFusedString.class), anns -> !IOFieldTools.isNullable(anns)); }
		@Override
		public <T extends IOInstance<T>> IOField<T, String> create(FieldAccessor<T> field){
			return new IOFieldFusedString<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.of(IOValue.class, (FieldAccessor<T> field, IOValue ann) -> {
					var namesPrefix  = field.getName() + FieldNames.GENERATED_FIELD_SEPARATOR;
					var numSizeName  = namesPrefix + "numSize";
					var encodingName = namesPrefix + "encoding";
					
					return new BehaviourRes<T>(List.of(
						new VirtualFieldDefinition<>(
							StoragePool.IO, encodingName,
							Encoding.class
						),
						new VirtualFieldDefinition<>(
							StoragePool.IO, namesPrefix + "chars",
							int.class,
							List.of(
								Annotations.make(IODependency.VirtualNumSize.class, Map.of("name", numSizeName)),
								IOValue.Unsigned.INSTANCE
							)
						),
						new VirtualFieldDefinition<>(
							StoragePool.IO, namesPrefix + "bytes",
							int.class,
							List.of(
								Annotations.make(IODependency.VirtualNumSize.class, Map.of("name", numSizeName)),
								IOValue.Unsigned.INSTANCE,
								Annotations.make(IODependency.class, Map.of("value", new String[]{encodingName}))
							)
						)
					));
				})
			);
		}
	}
	
	private IOField<CTyp, Encoding>     encodingField;
	private IOFieldPrimitive.FInt<CTyp> charCountField;
	private IOFieldPrimitive.FInt<CTyp> bytesField;
	
	
	public IOFieldFusedString(FieldAccessor<CTyp> accessor){
		super(accessor);
		initSizeDescriptor(SizeDescriptor.Unknown.of((ioPool, prov, value) -> bytesField.getValue(ioPool, value)));
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.HAS_NO_POINTERS);
	}
	
	@Override
	public void init(FieldSet<CTyp> ioFields){
		super.init(ioFields);
		var namesPrefix = getName() + FieldNames.GENERATED_FIELD_SEPARATOR;
		encodingField = ioFields.requireExact(Encoding.class, namesPrefix + "encoding");
		charCountField = ioFields.requireExactInt(namesPrefix + "chars");
		bytesField = ioFields.requireExactInt(namesPrefix + "bytes");
	}
	
	@Override
	public List<ValueGeneratorInfo<CTyp, ?>> getGenerators(){
		return List.of(
			new ValueGeneratorInfo<>(encodingField, new ValueGenerator.NoCheck<CTyp, Encoding>(){
				@Override
				public Encoding generate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod){
					var str = get(ioPool, instance);
					return Encoding.findBest(str);
				}
			}),
			new ValueGeneratorInfo<>(charCountField, new ValueGenerator.NoCheck<CTyp, Integer>(){
				@Override
				public Integer generate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod){
					return get(ioPool, instance).length();
				}
			}),
			new ValueGeneratorInfo<>(bytesField, new ValueGenerator.NoCheck<CTyp, Integer>(){
				@Override
				public Integer generate(VarPool<CTyp> ioPool, DataProvider provider, CTyp instance, boolean allowExternalMod) throws IOException{
					String   data = get(ioPool, instance);
					Encoding enc  = encodingField.get(ioPool, instance);
					return enc.calcSize(data);
				}
			})
		);
	}
	
	@Override
	public String get(VarPool<CTyp> ioPool, CTyp instance){
		return getNullable(ioPool, instance, () -> "");
	}
	@Override
	public boolean isNull(VarPool<CTyp> ioPool, CTyp instance){
		return isNullRawNullable(ioPool, instance);
	}
	
	@Override
	public void set(VarPool<CTyp> ioPool, CTyp instance, String value){
		super.set(ioPool, instance, switch(getNullability()){
			case DEFAULT_IF_NULL, NULLABLE -> value;
			case NOT_NULL -> Objects.requireNonNull(value);
		});
	}
	
	@Override
	public void write(VarPool<CTyp> ioPool, DataProvider provider, ContentWriter dest, CTyp instance) throws IOException{
		String   data = get(ioPool, instance);
		Encoding enc  = encodingField.get(ioPool, instance);
		enc.write(dest, data);
	}
	
	@Override
	public void read(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		Encoding enc   = encodingField.get(ioPool, instance);
		int      cc    = charCountField.getValue(ioPool, instance);
		var      bytes = bytesField.getValue(ioPool, instance);
		
		if(bytes<0 || bytes>(src instanceof RandomIO rio? rio.remaining() : provider.getSource().getIOSize())){
			throw new IOException("Illegal size: " + cc);
		}
		
		var buff = CharBuffer.allocate(cc);
		enc.read(new LimitedContentReader(src, bytes), buff);
		var data = buff.flip().toString();
		
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<CTyp> ioPool, DataProvider provider, ContentReader src, CTyp instance, GenericContext genericContext) throws IOException{
		var bytes = bytesField.getValue(ioPool, instance);
		src.skipExact(bytes);
	}
	
	@Override
	protected void throwInformativeFixedSizeError(){
		//TODO
		throw new RuntimeException("Strings do not support fixed size yet. In future a max string size will be defined by the user if they wish for it to be fixed size compatible.");
	}
	
	@Override
	public Optional<String> instanceToString(VarPool<CTyp> ioPool, CTyp instance, StringifySettings settings){
		var val = get(ioPool, instance);
		if(val == null || val.length() == 0) return Optional.empty();
		return Optional.of('"' + val + '"');
	}
}
