
# JDatablockFS
A java library that aims to store arbitrary data in a format that is compact, easily modifiable and flexible. Aimed for storing many small files efficiently while bypassing shortcomings of traditional file systems or be used to store complex data structures such as game save files with minimum effort.

***NOTE: any and all information is subject to change, the library is in development, use at your own risk, Performance and JVM version is not a priority for now***

## Usage


There are a few default implementations of the `IOInterface` (interface that is the basis for all required IO operations) such as:
 - ByteBufferData (simple to a byte[] but has limitations such as maximum size)
 - MemoryData (functionally similar to `List<byte>`)
 - IOFileData (uses a real `File` as the storage medium)

But you are free to create your own implementation for any kind of medium that supports random access read and write

The main brain of the library is a class called Cluster (cluster of data blocks called chunks that make chains)

Currently there is no user friendly way of interacting with it. (malloc OP is the only way for now) In the future a user friendly wrappers will be created that enable file path style data access or access by arbitrary data such as a UUID, int, long, string (and an array variant for things such as xyz vector) or a custom defined `IOInstance` for maximum flexibility.

## Examples


### Simple store of string in an auto formatted way in a linked list

```JAVA
private static void textTest() throws IOException{  
     
   Cluster cluster=Cluster.build(Cluster.Builder::withNewMemory);  
     
   IOList<String> textList=IOList.box(  
      StructLinkedList.build(b->b.withAllocator(cluster::userAlloc)  
                                 .withElementConstructor(AutoText::new)),  
      AutoText::getData,  
      AutoText::new);  
     
   try{  
        
      textList.addElement("this is in base 64");  
      textList.addElement("BA16");  
      textList.addElement("this is in ASCII");  
      textList.addElement("this is in UTF-8™");  
        
      for(String s : textList){  
         System.out.println(s);  
      }  
        
      textList.setElement(1, "this was base 16 but is now ASCII and resized to be larger");  
        
      for(String s : textList){  
         System.out.println(s);  
      }  
   }finally{  
      textList.free();  
   }  
}
```
___

### Custom object and custom value read/write example in a data flat list:
```JAVA
private static void objectTest() throws IOException{  
   class Dummy{  
      int value;  
        
      public Dummy(int value){  
         this.value=value;  
      }  
        
      @Override  
      public boolean equals(Object o){  
          if(this==o) return true;  
          return o instanceof Dummy dummy&&  
                 value==dummy.value;  
      }  
   }  
     
   class FooObject extends IOInstance{  
         
       @IOStruct.PrimitiveValue(index=0)  
       int foo;  
       @IOStruct.EnumValue(index=1)  
       NumberSize barSize=NumberSize.VOID;  
       @IOStruct.Value(index=2)  
       Dummy dummy;  
         
       public FooObject(){ }  
         
       public FooObject(int foo, NumberSize barSize, Dummy dummy){  
           this.foo=foo;  
           this.barSize=barSize;  
           this.dummy=dummy;  
       }  
         
       @IOStruct.Read  
       private Dummy readDummy(ContentReader source, Dummy oldVal) throws IOException{  
           int val=source.readInt4();  
           if(val==0) return null;  
           var newVal=oldVal==null?new Dummy(-1):oldVal;  
           newVal.value=val;  
           return newVal;  
       }  
         
       @IOStruct.Write  
       private void writeDummy(ContentWriter dest, Dummy source) throws IOException{  
           dest.writeInt4(source==null?0:source.value);  
       }  
         
       @IOStruct.Size(fixedSize=4)  
       private int sizeDummy(Dummy source){  
           return 4;  
       }  
         
       @Override  
       public boolean equals(Object o){  
           if(this==o) return true;  
           return o instanceof FooObject fooObject&&  
                  foo==fooObject.foo&&  
                  barSize==fooObject.barSize&&  
                  Objects.equals(dummy, fooObject.dummy);  
       }
   }  
     
   Cluster cluster=Cluster.build(Cluster.Builder::withNewMemory);  
     
   StructFlatList foos=StructFlatList.allocate(cluster::userAlloc, 2, FooObject::new);  
   try{  
       foos.addElement(new FooObject(1234, NumberSize.INT, new Dummy(999)));  
       foos.addElement(new FooObject(4321, NumberSize.SMALL_LONG, null));  
         
       for(FooObject foo : foos){  
          System.out.println(foo);  
       }  
   }finally{  
       foos.free();  
   }  
     
}
```
