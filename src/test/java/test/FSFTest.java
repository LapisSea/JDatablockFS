package test;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.IOList;
import com.lapissea.cfs.objects.StructFlatList;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.chunk.Chunk;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.ZeroArrays;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.lapissea.cfs.Config.*;

class FSFTest{
	
	static{
		System.setProperty("sun.java2d.opengl", "true");
//		System.setProperty("sun.java2d.d3d", "true");
//		Toolkit.getDefaultToolkit().setDynamicLayout(false);
		
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	
	public static void main(String[] args){
		try{
			
			LateInit<DataLogger> display=new LateInit<>(()->{
				if(DEBUG_VALIDATION){
					return new DisplayServer();
//					return new Display();
				}
				return new DataLogger.Blank();
			});
			
			var preBuf=new LinkedList<MemFrame>();
			
			var mem=new MemoryData();
			Cluster.class.getSimpleName();
			
			Cluster cluster=Cluster.build(b->b.withIO(mem));
			
			mem.onWrite=ids->{
				preBuf.add(new MemFrame(mem.readAll(), ids, new Throwable()));
				display.ifInited(d->{
					while(!preBuf.isEmpty()){
						d.log(preBuf.remove(0));
					}
				});
			};
			
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
			freeChunksTest(cluster);
			flatListTest(cluster);
			linkedListTest(cluster);
		}
		
	}
	
	private static void freeChunksTest(Cluster cluster) throws IOException{
		
		List<Chunk> chunk1=new ArrayList<>();
		List<Chunk> chunk2=new ArrayList<>();
		
		for(int i=0;i<5;i++){
			chunk1.add(cluster.userAlloc(10));
			chunk2.add(cluster.alloc(10));
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
		
		
		IOList<ChunkPointer> list=IOList.box(
			StructFlatList.allocate(cluster::userAlloc, 2, ChunkPointer.PtrFixed::new),
			ChunkPointer.PtrFixed::getValue,
			ChunkPointer.PtrFixed::new
		                                    );
		
		list.validate();
		list.addElement(new ChunkPointer(141));
		list.validate();
		list.addElement(new ChunkPointer(14351));
		list.validate();
		list.removeElement(0);
		list.validate();
		list.addElement(new ChunkPointer(2357));
		list.addElement(new ChunkPointer(2357));
		list.addElement(new ChunkPointer(2357));
		list.addElement(new ChunkPointer(2357));
		list.validate();
		list.removeElement(2);
		list.removeElement(1);
		list.removeElement(1);
		list.validate();
		list.addElement(new ChunkPointer(15254351));
		list.addElement(new ChunkPointer(15254351));
		list.addElement(new ChunkPointer(15254351));
		list.addElement(new ChunkPointer(15254351));
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
			StructLinkedList.build(b->b.withAllocator(cluster::userAlloc)
			                           .withElementConstructor(AutoText::new)),
			AutoText::getData,
			AutoText::new
		                              );
		
		cluster.validate();
		list.validate();
		list.addElement("ay lmao()111111");
		list.validate();
		cluster.validate();
		list.addElement("ay124 lmao()");
		list.validate();
		cluster.validate();
		list.removeElement(0);
		list.validate();
		cluster.validate();
		list.addElement("ay lmao()");
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
	
}
