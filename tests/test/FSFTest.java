package test;

import com.lapissea.cfs.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.StructFlatList;
import com.lapissea.cfs.objects.StructLinkedList;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.function.Supplier;

class FSFTest{
	
	static{
		LogUtil.Init.attach(LogUtil.Init.USE_CALL_POS|LogUtil.Init.USE_TABULATED_HEADER);
	}
	
	
	public static void main(String[] args) throws Throwable{
		
		var cluster=new Cluster(new MemoryData());

//		LogUtil.println(binary(makeMask(64)), binaryRangeFindZero(makeMask(64), 0, 64));
		flatListTest(cluster);
		linkedListTest(cluster);
		linkedListTest(cluster);
		linkedListTest(cluster);
		linkedListTest(cluster);
	}
	
	private static void flatListTest(Cluster cluster) throws IOException{
		cluster=new Cluster(new MemoryData());
		
		
		StructFlatList<ChunkPointer.PtrFixed> list=new StructFlatList<>(cluster.alloc(8), ChunkPointer.PtrFixed::new);
		list.validate();
		list.addElement(new ChunkPointer.PtrFixed(new ChunkPointer(141)));
		list.validate();
		list.addElement(new ChunkPointer.PtrFixed(new ChunkPointer(14351)));
		list.validate();
		list.removeElement(0);
		list.validate();
		list.addElement(new ChunkPointer.PtrFixed(new ChunkPointer(2357)));
		list.addElement(new ChunkPointer.PtrFixed(new ChunkPointer(2357)));
		list.addElement(new ChunkPointer.PtrFixed(new ChunkPointer(2357)));
		list.addElement(new ChunkPointer.PtrFixed(new ChunkPointer(2357)));
		list.validate();
		list.removeElement(2);
		list.removeElement(1);
		list.removeElement(1);
		list.validate();
		list.addElement(new ChunkPointer.PtrFixed(new ChunkPointer(15254351)));
		list.validate();

//		LogUtil.println(cluster.getData().hexdump());
//
//		for(ChunkPointer.PtrFixed o : list){
//			LogUtil.println(o);
//		}
		list.clear();
		list.validate();
		cluster.validate();

//		for(ChunkPointer.PtrFixed o : list){
//			LogUtil.println(o);
//		}
	}
	
	
	private static void linkedListTest(Cluster cluster) throws IOException{
		
		StructLinkedList<AutoText> list=new StructLinkedList<>(cluster.alloc(8), (Supplier<AutoText>)AutoText::new);
		
		cluster.validate();
		list.validate();
		list.addElement(new AutoText("ay lmao()111111"));
		list.validate();
		cluster.validate();
		list.addElement(new AutoText("ay124 lmao()"));
		list.validate();
		cluster.validate();
		list.removeElement(0);
		list.validate();
		cluster.validate();
		list.addElement(new AutoText("ay lmao()"));
		list.validate();
		cluster.validate();
		list.removeElement(1);
		cluster.validate();
		list.validate();
		
		list.addElement(new AutoText("!bro lmao xD!"));
		cluster.validate();
		list.validate();
		list.addElement(new AutoText("!kek?!?!?!"));
		cluster.validate();
		list.validate();
		list.removeElement(1);
		cluster.validate();
		list.validate();
		list.addElement(new AutoText("ay this works?"));
		cluster.validate();
		list.validate();
		
		LogUtil.println(list);

//		for(int i=0;i<list.size();i++){
//			LogUtil.println(list.getElement(i));
//		}

//		LogUtil.println();

//		LogUtil.println(cluster.getData().hexdump());
		list.clear();
		list.validate();
		cluster.validate();

//		LogUtil.println();
		LogUtil.println(cluster.getData().hexdump());
	}
	
}
