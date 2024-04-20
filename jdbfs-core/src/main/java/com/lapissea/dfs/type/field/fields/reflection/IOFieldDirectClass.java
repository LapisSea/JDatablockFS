package com.lapissea.dfs.type.field.fields.reflection;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedObject;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
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
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.NullFlagCompanyField;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.lapissea.dfs.type.field.StoragePool.IO;

public final class IOFieldDirectClass<T extends IOInstance<T>, V> extends NullFlagCompanyField<T, Class<V>>{
	
	@SuppressWarnings("unused")
	private static final class Usage<V> extends FieldUsage.InstanceOf<Class<V>>{
		public Usage(){
			super((Class<Class<V>>)(Object)Class.class, Set.of(IOFieldDirectClass.class));
		}
		@Override
		public <T extends IOInstance<T>> IOField<T, Class<V>> create(FieldAccessor<T> field){
			return new IOFieldDirectClass<>(field);
		}
		@Override
		public <T extends IOInstance<T>> List<Behaviour<?, T>> annotationBehaviour(Class<IOField<T, ?>> fieldType){
			return List.of(
				Behaviour.of(IONullability.class, (field1, ann1) -> {
					return BehaviourSupport.ioNullability(field1, ann1);
				}),
				Behaviour.of(IOValue.class, (field, ann) -> {
					return new BehaviourRes<>(new VirtualFieldDefinition<T, Integer>(
						IO,
						FieldNames.ID(field),
						int.class,
						List.of(Annotations.make(IODependency.VirtualNumSize.class), IOValue.Unsigned.INSTANCE)
					));
				})
			);
		}
	}
	
	private IOFieldPrimitive.FInt<T> id;
	
	public IOFieldDirectClass(FieldAccessor<T> accessor){
		super(accessor);
		initSizeDescriptor(SizeDescriptor.Fixed.empty());
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
				var clazz = get(ioPool, instance);
				var cid   = provider.getTypeDb().toID(clazz, allowExternalMod);
				return cid.val();
			}
		}));
	}
	@Override
	public void write(VarPool<T> ioPool, DataProvider provider, ContentWriter dest, T instance) throws IOException{ }
	@Override
	public void read(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{
		Class<V> data;
		var      cid = id.getValue(ioPool, instance);
		if(cid == 0){
			if(nullable()) data = null;
			else{
				throw new MalformedObject(this + " is null");
			}
		}else{
			var typ = provider.getTypeDb().fromID(cid);
			if(!(typ instanceof IOType.TypeRaw roo)){
				throw new MalformedObject(this + " has an invalid type of: " + typ);
			}
			//noinspection unchecked
			data = (Class<V>)roo.getTypeClass(provider.getTypeDb());
		}
		set(ioPool, instance, data);
	}
	
	@Override
	public void skip(VarPool<T> ioPool, DataProvider provider, ContentReader src, T instance, GenericContext genericContext) throws IOException{ }
}
