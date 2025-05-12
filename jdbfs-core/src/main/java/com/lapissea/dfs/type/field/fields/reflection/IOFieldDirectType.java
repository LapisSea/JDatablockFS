package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedObject;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.BehaviourSupport;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.VirtualFieldDefinition;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOUnsafeValue;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.type.field.StoragePool.IO;

@IOUnsafeValue.Mark
public final class IOFieldDirectType<T extends IOInstance<T>> extends NullFlagCompanyField<T, Type>{
	
	@SuppressWarnings("unused")
	private static final class Usage implements FieldUsage{
		@Override
		public <T extends IOInstance<T>> IOField<T, Type> create(FieldAccessor<T> field){
			return new IOFieldDirectType<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IONullability.class, BehaviourSupport::ioNullability),
				Behaviour.of(IOValue.class, (field, ann) -> {
					return new BehaviourRes<>(
						List.of(
							new VirtualFieldDefinition<T, Integer>(
								IO,
								FieldNames.ID(field),
								int.class,
								List.of(Annotations.make(IODependency.VirtualNumSize.class), IOValue.Unsigned.INSTANCE)
							)),
						Set.of(IOUnsafeValue.class)
					);
				})
			);
		}
		public Class<Type> getType(){
			return Type.class;
		}
		@Override
		public boolean isCompatible(Type type, GetAnnotation annotations){
			if(!annotations.isPresent(IOUnsafeValue.class)){
				return false;
			}
			return UtilL.instanceOf(Utils.typeToRaw(type), Type.class);
		}
		@Override
		@SuppressWarnings("rawtypes")
		public Set<Class<? extends IOField>> listFieldTypes(){ return Set.of(IOFieldDirectType.class); }
	}
	
	private IOFieldPrimitive.FInt<T> id;
	
	public IOFieldDirectType(FieldAccessor<T> accessor){
		super(accessor);
		initSizeDescriptor(SizeDescriptor.Fixed.empty());
	}
	@Override
	protected Set<TypeFlag> computeTypeFlags(){
		return Set.of(TypeFlag.HAS_NO_POINTERS);
	}
	
	@Override
	public void init(FieldSet<T> fields){
		super.init(fields);
		id = fields.requireExactInt(FieldNames.ID(getAccessor()));
	}
	
	@Override
	public List<ValueGeneratorInfo<T, ?>> getGenerators(){
		return Utils.concat(super.getGenerators(), new ValueGeneratorInfo<>(id, new ValueGenerator<>(){
			@Override
			public boolean shouldGenerate(VarPool<T> ioPool, DataProvider provider, T instance){
				var cid   = id.getValue(ioPool, instance);
				var clazz = get(ioPool, instance);
				return (cid == 0) != (clazz == null);
			}
			@Override
			public Integer generate(VarPool<T> ioPool, DataProvider provider, T instance, boolean allowExternalMod) throws IOException{
				var type = get(ioPool, instance);
				if(type == null && nullable()) throw new NullPointerException(IOFieldDirectType.this + " is null");
				var cid = provider.getTypeDb().toID(type, allowExternalMod);
				return cid.val();
			}
		}));
	}
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{ }
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		Type data;
		var  cid = id.getValue(ioPool, instance);
		if(cid == 0){
			if(nullable()) data = null;
			else{
				throw new MalformedObject(this + " is null");
			}
		}else{
			var db  = provider.getTypeDb();
			var typ = db.fromID(cid);
			data = typ.generic(db);
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{ }
}
