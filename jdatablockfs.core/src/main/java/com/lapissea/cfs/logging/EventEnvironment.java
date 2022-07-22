package com.lapissea.cfs.logging;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.lapissea.cfs.type.StagedInit.STATE_DONE;

public class EventEnvironment extends IOInstance<EventEnvironment>{
	
	private static final long NO_ID=-1;
	
	public static class EventReference extends IOInstance<EventReference>{
		
		private long  refId=NO_ID;
		private Event eventObj;
		
		@IOValue
		private String name;
		
		
		public EventReference(){}
		public EventReference(Event event, String name){
			this.eventObj=Objects.requireNonNull(event);
			this.name=Objects.requireNonNull(name);
		}
		
		@IOValue
		private void setRefId(long refId){
			eventObj=null;
			this.refId=refId;
		}
		
		@IOValue
		private long getRefId(){
			if(refId==NO_ID){
				if(eventObj==null) throw new IllegalStateException("Reference has no id");
				var id=eventObj.id;
				if(id==NO_ID) throw new IllegalStateException(eventObj+" was not logged");
				refId=id;
			}
			return refId;
		}
		
		public Event getEvent(EventEnvironment env){
			if(eventObj==null){
				eventObj=env.eventById(refId);
			}
			return eventObj;
		}
		public String getName(){
			return name;
		}
	}
	
	public static class Timeline extends IOInstance<Timeline>{
		@IOValue
		@IONullability(IONullability.Mode.NULLABLE)
		private String name;
		
		@IOValue
		@IODependency("name")
		private List<Event> events=new ArrayList<>();
		
		public Timeline(){}
		public Timeline(String name){
			this.name=name;
		}
		
		public String getName(){
			return name;
		}
		public List<Event> getEvents(){
			return events;
		}
		
		@IOValue
		public void setEvents(List<Event> events){
			this.events=events;
			for(Event e : events){
				e.timelineName=name;
			}
		}
	}
	
	public static class Event extends IOInstance<Event>{
		
		private String timelineName;
		
		@IOValue
		@IODependency.VirtualNumSize
		private long id=NO_ID;
		
		@IOValue
		@IODependency.VirtualNumSize
		private long timelinePos;
		
		@IOValue
		private String eventName;
		
		@IOValue
		private List<EventReference> references;
		
		public Event(){}
		public Event(long timelinePos, String timelineName, String eventName, List<EventReference> references){
			this.timelinePos=timelinePos;
			if(timelinePos<0) throw new IllegalArgumentException(timelinePos+"");
			this.timelineName=Objects.requireNonNull(timelineName);
			this.eventName=eventName;
			this.references=List.copyOf(references);
		}
		
		public String getTimelineName(){
			return timelineName;
		}
		public long getTimelinePos(){
			return timelinePos;
		}
		public String getEventName(){
			return eventName;
		}
		@IOValue
		public void setEventName(String eventName){
			this.eventName=eventName;
		}
		public List<EventReference> getReferences(){
			return references;
		}
	}
	
	public class AdaptedLog<T>{
		private final BiConsumer<Consumer<Event>, T> addapter;
		public AdaptedLog(BiConsumer<Consumer<Event>, T> addapter){
			this.addapter=addapter;
		}
		
		private class ResolveEvent implements Supplier<List<Event>>{
			
			private T           value;
			private List<Event> resolved;
			
			public ResolveEvent(T value){
				this.value=value;
			}
			
			@Override
			public List<Event> get(){
				resolve();
				return resolved;
			}
			
			private synchronized void resolve(){
				if(resolved!=null) return;
				List<Event> arr=new ArrayList<>();
				addapter.accept(arr::add, Objects.requireNonNull(value));
				resolved=List.copyOf(arr);
				value=null;
				for(Event event : arr){
					logEvent(event);
				}
			}
		}
		
		
		public Supplier<List<Event>> log(T value){
			var r=new ResolveEvent(value);
			pending.add(r);
			return r;
		}
	}
	
	@IOValue
	private long   idCounter;
	@IOValue
	private String name;
	
	@IOValue
	private       List<Timeline>                   timelines=new ArrayList<>();
	private final List<AdaptedLog<?>.ResolveEvent> pending  =new ArrayList<>();
	
	public EventEnvironment(){}
	public EventEnvironment(String name){
		this.name=name;
	}
	
	public <T> AdaptedLog<T> addaptLog(BiConsumer<Consumer<Event>, T> addapter){
		return new AdaptedLog<>(addapter);
	}
	
	
	private void logEvent(Event event){
		Objects.requireNonNull(event);
		
		if(event.id!=NO_ID){
			throw new IllegalArgumentException("Event has already been logged");
		}
		event.id=newId();
		
		var n=event.timelineName;
		
		for(var timeline : timelines){
			if(timeline.name.equals(n)){
				timeline.events.add(event);
				return;
			}
		}
		var t=new Timeline(n);
		t.events.add(event);
		timelines.add(t);
	}
	
	private synchronized long newId(){
		return idCounter++;
	}
	
	private Event eventById(long id){
		if(id==NO_ID) throw new IllegalArgumentException();
		return timelines.stream().flatMap(t->t.events.stream()).filter(e->e.id==id).findAny().orElseGet(()->{
			if(pending.isEmpty()) throw new NoSuchElementException();
			flushPending();
			return eventById(id);
		});
	}
	
	public List<Timeline> getTimelines(){
		return timelines;
	}
	
	
	public MemoryData<?> emit() throws IOException{
		preloadTypes();
		flushPending();
		var mem    =MemoryData.builder().build();
		var cluster=Cluster.init(mem);
		cluster.getRootProvider().provide(this, new ObjectID(name));
		return mem;
	}
	
	static{
//		Runner.compileTask(()->{
//			ContiguousStructPipe.of(EventEnvironment.class);
//			Struct.of(Event.class);
//			Struct.of(EventReference.class);
//			Struct.of(Timeline.class);
//			Struct.of(Cluster.RootRef.class);
//		});
	}
	
	private void preloadTypes(){
		ContiguousStructPipe.of(EventEnvironment.class, STATE_DONE);
		Struct.of(Event.class, STATE_DONE);
		Struct.of(EventReference.class, STATE_DONE);
		Struct.of(Timeline.class, STATE_DONE);
		Struct.of(Cluster.RootRef.class, STATE_DONE);
	}
	
	
	private void flushPending(){
		for(int i=pending.size()-1;i>=0;i--){
			pending.remove(i).resolve();
		}
		timelines.sort(Comparator.comparing(Timeline::getName));
	}
	
}
