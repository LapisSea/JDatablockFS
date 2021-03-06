package test;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.extensions.BlockMapCluster;
import com.lapissea.cfs.conf.AllocateTicket;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.IOTypeLayout;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.boxed.IOLong;
import com.lapissea.cfs.objects.boxed.IOVoid;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.cfs.tools.Common;
import com.lapissea.cfs.tools.DataLogger;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.ZeroArrays;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.lapissea.cfs.GlobalConfig.*;

class FSFTest{
	
	static{
		System.setProperty("sun.java2d.opengl", "true");
	}
	
	
	public static void main(String[] args){
		
		try{
			LateInit<DataLogger> display=Common.initAndLogger();
			
			if(DEBUG_VALIDATION) display.block();
			
			MemoryData mem    =Common.newLoggedMemory(display);
			Cluster    cluster=Cluster.build(b->b.withIO(mem));
			
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
		
		var test=new IOTypeLayout(StructLinkedList.class, new int[]{2, 4}, new IOTypeLayout(IOLong.class));
		
		var c=AllocateTicket.fitAndPopulate(test).asUserData(new IOType(IOTypeLayout.class)).submit(cluster);
		
		IOTypeLayout layout=new IOTypeLayout();
		c.io(io->layout.readStruct(cluster, io));
		
		c.freeChaining();
		
		LogUtil.println(test.toShortString());
		LogUtil.println(layout.toShortString());
		
		//cluster.pack();
		
		if(true) return;
		
		for(int i=0;i<2;i++){
			objectClusterTest(cluster);
			if(true) return;
			packTest(cluster);
			freeChunksTest(cluster);
			flatListTest(cluster);
			linkedListTest(cluster);
		}
		
		cluster.pack();
	}
	
	private static void objectClusterTest(Cluster cluster) throws IOException{
		BlockMapCluster<AutoText> objs=new BlockMapCluster<>(cluster, AutoText.class);
		byte[]                    bb  =cluster.getData().readAll();
		Cluster.build(b->b.withMemoryView(bb))
		       .memoryWalk((Cluster.PointerStack e)->false);
		objs.defineBlock(new AutoText("ay"), io->{
			io.writeInts1("lmao".getBytes());
			io.trim();
		});
		
		objs.deleteBlock(new AutoText("ay"));
		
		objs.pack();
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
		
		cluster.pack();
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
		cluster.pack();
		
		cluster.validate();
	}
	
}
