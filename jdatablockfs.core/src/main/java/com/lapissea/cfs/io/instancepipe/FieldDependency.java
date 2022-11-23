package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FieldDependency<T extends IOInstance<T>>{
	
	public record Ticket<T extends IOInstance<T>>(
		FieldSet<T> writeFields,
		FieldSet<T> readFields,
		List<IOField.ValueGeneratorInfo<T, ?>> generators
	){}
	
	
	private final Map<IOField<T, ?>, Ticket<T>> singleDependencyCache    =new HashMap<>();
	private final ReadWriteLock                 singleDependencyCacheLock=new ReentrantReadWriteLock();
	
	private final Map<FieldSet<T>, Ticket<T>> multiDependencyCache    =new HashMap<>();
	private final ReadWriteLock               multiDependencyCacheLock=new ReentrantReadWriteLock();
	
	private final FieldSet<T> allFields;
	
	public FieldDependency(FieldSet<T> allFields){
		this.allFields=allFields;
	}
	
	public Ticket<T> getDeps(Set<String> names){
		return getDeps(FieldSet.of(names.stream().map(n->allFields.byName(n).orElseThrow())));
	}
	public Ticket<T> getDeps(FieldSet<T> selectedFields){
		if(selectedFields.size()==1){
			return getDeps(selectedFields.get(0));
		}
		
		var r=multiDependencyCacheLock.readLock();
		r.lock();
		try{
			var cached=multiDependencyCache.get(selectedFields);
			if(cached!=null) return cached;
		}finally{
			r.unlock();
		}
		var w=multiDependencyCacheLock.writeLock();
		w.lock();
		try{
			var cached=multiDependencyCache.get(selectedFields);
			if(cached!=null) return cached;
			
			var field=generateFieldsDependency(selectedFields);
			multiDependencyCache.put(selectedFields, field);
			return field;
		}finally{
			w.unlock();
		}
		
	}
	
	public Ticket<T> getDeps(IOField<T, ?> selectedField){
		var r=singleDependencyCacheLock.readLock();
		r.lock();
		try{
			var cached=singleDependencyCache.get(selectedField);
			if(cached!=null) return cached;
		}finally{
			r.unlock();
		}
		
		var w=singleDependencyCacheLock.writeLock();
		w.lock();
		try{
			var cached=singleDependencyCache.get(selectedField);
			if(cached!=null) return cached;
			
			var field=generateFieldDependency(selectedField);
			singleDependencyCache.put(selectedField, field);
			return field;
		}finally{
			w.unlock();
		}
	}
	
	private Ticket<T> generateFieldsDependency(FieldSet<T> selectedFields){
		if(selectedFields.isEmpty()){
			return new Ticket<T>(FieldSet.of(), FieldSet.of(), List.of());
		}
		selectedFields.forEach(this::checkExistenceOfField);
		if(selectedFields.size()==allFields.size()){
			return new Ticket<>(allFields, allFields, collectGenerators(allFields));
		}
		var writeFields=new HashSet<IOField<T, ?>>();
		var readFields =new HashSet<IOField<T, ?>>();
		for(IOField<T, ?> selectedField : selectedFields){
			var part=getDeps(selectedField);
			writeFields.addAll(part.writeFields);
			readFields.addAll(part.readFields);
		}
		return makeTicket(writeFields, readFields);
	}
	
	private Ticket<T> generateFieldDependency(IOField<T, ?> selectedField){
		checkExistenceOfField(selectedField);
		
		Set<IOField<T, ?>> selectedWriteFieldsSet=new HashSet<>();
		selectedWriteFieldsSet.add(selectedField);
		Set<IOField<T, ?>> selectedReadFieldsSet=new HashSet<>();
		selectedReadFieldsSet.add(selectedField);
		
		boolean shouldRun=true;
		while(shouldRun){
			shouldRun=false;
			
			for(IOField<T, ?> field : List.copyOf(selectedWriteFieldsSet)){
				if(field.hasDependencies()){
					if(selectedWriteFieldsSet.addAll(field.getDependencies())){
						shouldRun=true;
					}
				}
				var gens=field.getGenerators();
				if(gens!=null){
					for(var gen : gens){
						if(selectedWriteFieldsSet.add(gen.field())){
							shouldRun=true;
						}
					}
				}
			}
			for(IOField<T, ?> field : List.copyOf(selectedReadFieldsSet)){
				if(field.hasDependencies()){
					if(selectedReadFieldsSet.addAll(field.getDependencies())){
						shouldRun=true;
					}
				}
			}
			
			//Find field and add to read fields when the field is skipped but is a dependency of another
			// skipped field that may need the dependency to correctly skip
			for(IOField<T, ?> field : List.copyOf(selectedReadFieldsSet)){
				var index=allFields.indexOf(field);
				if(index<=0) continue;
				
				var before=new ArrayList<>(allFields.subList(0, index));
				
				before.removeIf(selectedReadFieldsSet::contains);
				
				for(IOField<T, ?> skipped : before){
					//is skipped field dependency of another skipped field whos size may depend on it.
					if(before.stream().filter(e->!e.getSizeDescriptor().hasFixed())
					         .flatMap(IOField::dependencyStream)
					         .anyMatch(e->skipped.streamUnpackedFields().anyMatch(s->s==e))){
						selectedReadFieldsSet.add(skipped);
						shouldRun=true;
					}
				}
				
			}
			
			for(IOField<T, ?> field : List.copyOf(selectedWriteFieldsSet)){
				if(field.getSizeDescriptor().hasFixed()){
					continue;
				}
				
				var index=allFields.indexOf(field);
				if(index==-1) throw new AssertionError();//TODO handle fields in fields
				for(int i=index+1;i<allFields.size();i++){
					if(selectedWriteFieldsSet.add(allFields.get(i))){
						shouldRun=true;
					}
				}
			}
		}
		
		return makeTicket(selectedWriteFieldsSet, selectedReadFieldsSet);
	}
	
	private static <T extends IOInstance<T>> List<IOField.ValueGeneratorInfo<T, ?>> collectGenerators(Collection<IOField<T, ?>> writeFields){
		return Utils.nullIfEmpty(writeFields.stream().flatMap(IOField::generatorStream).toList());
	}
	
	private FieldSet<T> fieldSetToOrderedList(FieldSet<T> source, Set<IOField<T, ?>> fieldsSet){
		List<IOField<T, ?>> result=new ArrayList<>(fieldsSet.size());
		for(IOField<T, ?> f : source){
			var iter      =f.streamUnpackedFields().iterator();
			var anyRemoved=false;
			while(iter.hasNext()){
				var fi=iter.next();
				if(fieldsSet.remove(fi)) anyRemoved=true;
			}
			
			if(anyRemoved){
				result.add(f);
			}
		}
		if(!fieldsSet.isEmpty()){
			throw new IllegalStateException(fieldsSet+"");
		}
		return FieldSet.of(result);
	}
	
	private Ticket<T> makeTicket(Set<IOField<T, ?>> writeFields, Set<IOField<T, ?>> readFields){
		var w=fieldSetToOrderedList(allFields, writeFields);
		var r=fieldSetToOrderedList(allFields, readFields);
		var g=collectGenerators(writeFields);
		return new Ticket<>(w, r, g);
	}
	
	private void checkExistenceOfField(IOField<T, ?> selectedField){
		for(IOField<T, ?> field : allFields){
			if(field==selectedField){
				return;
			}
		}
		throw new IllegalArgumentException(selectedField+" is not listed!");
	}
}