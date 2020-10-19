package test;

import com.google.gson.GsonBuilder;
import com.lapissea.cfs.cluster.AllocateTicket;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.boxed.IOLong;
import com.lapissea.cfs.objects.boxed.IOVoid;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.tools.DataLogger;
import com.lapissea.cfs.tools.DisplayServer;
import com.lapissea.cfs.tools.MemFrame;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.ZeroArrays;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

import static com.lapissea.cfs.Config.*;

class FSFTest{
	
	static{
		System.setProperty("sun.java2d.opengl", "true");
//		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
		LogUtil.Init.attach(0);
	}
	
	
	public static void main(String[] args){
		try{
			Map<String, String> config=new HashMap<>();
			try(var r=new FileReader(new File("config.json"))){
				new GsonBuilder().create().<Map<String, Object>>fromJson(r, HashMap.class).forEach((k, v)->config.put(k, TextUtil.toString(v)));
			}catch(Exception ignored){ }
			
			System.setProperty("com.lapissea.cfs.tools.DisplayServer.THREADED_OUTPUT", config.getOrDefault("threadedOutput", ""));
			if(Boolean.parseBoolean(config.getOrDefault("fancyPrint", "true"))){
				LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
			}
			
			LateInit<DataLogger> display=new LateInit<>(()->{
				if(DEBUG_VALIDATION){
					Object jarPath=config.get("serverDisplayJar");
					return new DisplayServer(jarPath==null?null:jarPath.toString());
				}
				return new DataLogger.Blank();
			});
			
			display.block();
			
			var     mem    =new MemoryData();
			Cluster cluster=Cluster.build(b->b.withIO(mem));
			
			if(DEBUG_VALIDATION){
				var preBuf=new LinkedList<MemFrame>();
				mem.onWrite=ids->{
					preBuf.add(new MemFrame(mem.readAll(), ids, new Throwable()));
					display.ifInited(d->{
						while(!preBuf.isEmpty()){
							d.log(preBuf.remove(0));
						}
					});
				};
				mem.onWrite.accept(ZeroArrays.ZERO_LONG);
			}
			
			try{
				doTests(cluster);
			}finally{
				display.block();
				mem.onWrite.accept(ZeroArrays.ZERO_LONG);
				display.get().finish();
			}
			
		}catch(Throwable e1){
			e1.printStackTrace();
		}
		
	}
	
	private static void doTests(Cluster cluster) throws IOException{
		
		
		for(int i=0;i<2;i++){
			packTest(cluster);
			freeChunksTest(cluster);
			flatListTest(cluster);
			linkedListTest(cluster);
		}
		
	}
	
	private static void freeChunksTest(Cluster cluster) throws IOException{
		
		List<Chunk> chunk1=new ArrayList<>();
		List<Chunk> chunk2=new ArrayList<>();
		
		AllocateTicket t1=AllocateTicket.bytes(100).asUserData(new IOType(IOVoid.class));
		AllocateTicket t2=t1.withBytes(10);
		for(int i=0;i<5;i++){
			chunk1.add(t1.submit(cluster));
			chunk2.add(t2.submit(cluster));
		}

//		cluster.batchFree(()->{
		for(Chunk chunk : chunk1){
			chunk.freeChaining();
		}
//		});
		
		for(Chunk chunk : chunk2){
			chunk.freeChaining();
		}
	}
	
	private static void flatListTest(Cluster cluster) throws IOException{
		
		
		IOList<IOLong> list=cluster.constructType(AllocateTicket.user(new IOType(StructLinkedList.class, IOLong.class)));
		
		list.validate();
		list.addElement(new IOLong(141));
		list.validate();
		list.addElement(new IOLong(14351));
		list.validate();
		list.removeElement(0);
		list.validate();
		list.addElement(new IOLong(2357));
		list.addElement(new IOLong(2357));
		list.addElement(new IOLong(2357));
		list.addElement(new IOLong(2357));
		list.validate();
		list.removeElement(2);
		list.removeElement(1);
		list.removeElement(1);
		list.validate();
		list.addElement(new IOLong(15254351));
		list.addElement(new IOLong(15254351));
		list.addElement(new IOLong(15254351));
		list.addElement(new IOLong(15254351));
		list.validate();
		
		list.removeElement(1);
		list.removeElement(3);

//		LogUtil.println(cluster.getData().hexdump());
//
//		for(ChunkPointer.PtrFixed o : list){
//			LogUtil.println(o);
//		}
		list.free();
		cluster.validate();
	}
	
	
	private static void linkedListTest(Cluster cluster) throws IOException{
		
		IOList<String> list=IOList.box(
			cluster.constructType(AllocateTicket.user(new IOType(StructLinkedList.class, AutoText.class))),
			AutoText::getData,
			AutoText::new
		                              );
		
		cluster.validate();
		list.validate();
		list.addElement("ay lmao()111111");
		list.validate();
		cluster.validate();
		list.addElement("ay124 lmao(]");
		list.validate();
		cluster.validate();
		list.removeElement(0);
		list.validate();
		cluster.validate();
		list.addElement("ay lmao(}");
		list.validate();
		cluster.validate();
		list.removeElement(1);
		cluster.validate();
		list.validate();
		
		list.addElement("!bro lmao xD!");
		cluster.validate();
		list.validate();
		list.addElement("!kek.?!?!?!");
		list.addElement("!kek.?!?!?!");
		list.addElement("!kek.?!?!?!");
		list.addElement("!kek.?!?!?!");
		cluster.validate();
		list.validate();
		list.removeElement(1);
		cluster.validate();
		list.validate();
		list.addElement("ay this works?");
		cluster.validate();
		list.validate();
		
		list.setElement(2, "hahaah ay this is long?!?!?!?!??!?!?!??!?!?!!??!");
		
		LogUtil.println(list);
//		throw new RuntimeException();

//		for(int i=0;i<list.size();i++){
//			LogUtil.println(list.getElement(i));
//		}

//		LogUtil.println();

//		LogUtil.println(cluster.getData().hexdump());
		
		list.free();
		cluster.validate();
	}
	
	private static void packTest(Cluster cluster) throws IOException{
		StructLinkedList<AutoText> raw=cluster.constructType(AllocateTicket.user(new IOType(StructLinkedList.class, AutoText.class)));
		IOList<String> list=IOList.box(
			raw,
			AutoText::getData,
			AutoText::new
		                              );
		
		list.addElement("ay lmao()111111");
		list.addElement("ay");
		list.addElement("ay1314 lmao(]");
		list.addElement("ay lmao(}");
		list.removeElement(1);
		Chunk ch=AllocateTicket.bytes(223).submit(cluster);
		list.addElement("!bro lmao xD!?!?!?!??!");
		list.addElement("!kek.?!?!?!");
		ch.freeChaining();
		list.setElement(2, "hahaah ay this is long?!?!?!?!??!?!?!??!?!?!!??!");
		
		var rawSame=cluster.constructType(raw.getContainer());
		assert rawSame==raw;
		
		cluster.pack();
		list.free();
		
		cluster.validate();
	}
	
}
