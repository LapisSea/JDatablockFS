package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.exceptions.IncompatibleVersionTransform;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Versioning{
	
	public static final Versioning JUST_FAIL = new Versioning(Set.of(), List.of());
	
	private final EnumSet<VersioningOptions>         options;
	private final Map<String, VersionTransformer<?>> transformers;
	
	public Versioning(Set<VersioningOptions> options, List<VersionTransformer<?>> transformers){
		this.options = options.isEmpty()? EnumSet.noneOf(VersioningOptions.class) : EnumSet.copyOf(options);
		this.transformers = transformers.stream().collect(Collectors.toMap(t -> t.matchingClassName, Function.identity()));
	}
	
	
	private record FieldOp(String name, FieldHandler op){ }
	
	private interface FieldHandler{
		<T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns);
		
		FieldHandler COPY = new FieldHandler(){
			@Override
			public String reportName(){ return "COPY"; }
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				//noinspection unchecked
				var f = (IOField<T, Object>)realT.getFields().requireByName(name);
				f.set(null, realIns, oldIns.byName(name));
			}
		}, NEW_DEFAULT    = new FieldHandler(){
			@Override
			public String reportName(){ return "NEW_DEFAULT"; }
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				var f = realT.getFields().requireByName(name);
				
				if(!f.nullable() && f.isNull(null, realIns)){
					throw new IncompatibleVersionTransform(
						"Can not automatically create new field: " + f.getName() +
						" because it is non null but is not initialized by default"
					);
				}
			}
		}, REMOVE         = new FieldHandler(){
			@Override
			public String reportName(){ return "REMOVE"; }
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				//Nothing to do, just lose the data
			}
		}, VAL_TO_LIST    = new FieldHandler(){
			@Override
			public String reportName(){ return "VAL_TO_LIST"; }
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				var val = oldIns.byName(name);
				//noinspection unchecked
				var field = (IOField<T, Object>)realT.getFields().requireByName(name);
				if(val != null){
					var arg = ((ParameterizedType)field.getGenericType(null)).getActualTypeArguments()[0];
					Utils.typeToRaw(arg).cast(val);
				}
				var l = new ArrayList<>();
				l.add(val);
				field.set(null, realIns, l);
			}
		}, VAL_TO_ARRAY   = new FieldHandler(){
			@Override
			public String reportName(){ return "VAL_TO_ARRAY"; }
			@Override
			public <T extends IOInstance<T>> void apply(String name, Struct<T> realT, T realIns, UnpackedInstance oldIns){
				
				throw new NotImplementedException();
			}
		};
		String reportName();
	}
	
	private <T extends IOInstance<T>> VersionTransformer<T> incompatibleTransform(ClassVersionDiff diff, String reason){
		var name   = diff.real().getName();
		var report = "Incompatible transform of " + name + " because: " + reason;
		return new VersionTransformer<>(name, report, unpacked -> {
			var sj = new StringJoiner("\n");
			sj.add("Could not transform: " + diff.real().getName());
			sj.add("Reason: " + reason);
			if(!diff.newFields().isEmpty()) sj.add("New fields: " + diff.newFields());
			if(!diff.changedFields().isEmpty()) sj.add("Changed fields: " + diff.changedFields());
			if(!diff.removedFields().isEmpty()) sj.add("Removed fields: " + diff.removedFields());
			throw new IncompatibleVersionTransform(sj.toString());
		});
	}
	
	private <T extends IOInstance<T>> VersionTransformer<T> autoOpsTransform(Struct<T> struct, ClassVersionDiff diff){
		
		
		var excludeSet = Stream.of(diff.newFields(), diff.changedFields()).flatMap(Collection::stream).collect(Collectors.toSet());
		
		List<FieldOp> autoOps =
			struct.getRealFields()
			      .map(IOField::getName)
			      .filtered(n -> !excludeSet.contains(n))
			      .map(n -> new FieldOp(n, FieldHandler.COPY))
			      .collectToList();
		
		if(!diff.newFields().isEmpty()){
			if(options.contains(VersioningOptions.ATTEMPT_NEW_VALUE_DEFAULT)){
				for(var name : diff.newFields()){
					autoOps.add(new FieldOp(name, FieldHandler.NEW_DEFAULT));
				}
			}else{
				return incompatibleTransform(diff, "Could not handle new fields");
			}
		}
		
		if(!diff.changedFields().isEmpty()){
			if(options.contains(VersioningOptions.AUTO_COLLECTION_INTERPRET)){
				for(var name : diff.changedFields()){
					var field = struct.getFields().requireByName(name);
					if(field.typeFlag(IOField.DYNAMIC_FLAG)){
						return incompatibleTransform(diff, "Can not handle dynamic collection transform");
					}
					var type = field.getGenericType(null);
					
					if(type instanceof ParameterizedType parm &&
					   parm.getActualTypeArguments().length == 1 &&
					   UtilL.instanceOf((Class<?>)parm.getRawType(), List.class)
					){
						autoOps.add(new FieldOp(name, FieldHandler.VAL_TO_LIST));
						continue;
					}
					if(Utils.typeToRaw(type).isArray()){
						autoOps.add(new FieldOp(name, FieldHandler.VAL_TO_ARRAY));
						continue;
					}
					
					return incompatibleTransform(diff, "Could not handle changed fields");
				}
			}else{
				return incompatibleTransform(diff, "Could not handle changed fields");
			}
		}
		
		if(!diff.removedFields().isEmpty()){
			if(options.contains(VersioningOptions.AUTO_REMOVE)){
				for(var name : diff.removedFields()){
					autoOps.add(new FieldOp(name, FieldHandler.REMOVE));
				}
			}else{
				return incompatibleTransform(diff, "Could not handle removed fields");
			}
		}
		
		var finalOps = List.copyOf(autoOps);
		
		var name = diff.real().getName();
		var report = TextUtil.toTable(
			name,
			List.of(finalOps.stream().collect(Collectors.toMap(op -> op.name, op -> op.op.reportName())))
		).replaceAll("\n=+", "");
		
		return new VersionTransformer<>(name, report, unpacked -> {
			var inst = struct.make();
			for(var fieldOp : finalOps){
				fieldOp.op.apply(fieldOp.name, struct, inst, unpacked);
			}
			return inst;
		});
	}
	
	public VersionTransformer<?> createTransformer(ClassVersionDiff diff){
		var transformer = transformers.get(diff.real().getName());
		if(transformer != null) return transformer;
		
		
		var structO = Struct.tryOf(diff.real());
		if(structO.isPresent()){
			return autoOpsTransform(structO.get(), diff);
		}
		
		throw new NotImplementedException("Could not handle\n" + diff);
	}
}
