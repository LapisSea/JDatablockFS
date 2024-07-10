/*
 * Class derived from: https://github.com/vsonnier/hppcrt
 * Refactored generated IntHashSet to be a standalone/only standard imports class that can be easily imported without many unnecessary files.


ACKNOWLEDGEMENT
===============
HPPC-RT ("Realtime")
Copyright 2013-2019 Vincent Sonnier

HPPC-RT borrowed code, ideas or both from:

* HPPC, http://labs.carrotsearch.com/hppc.html, by Carrot Search s.c., Boznicza 11/57, 61-751 Poznan, Poland.
   (Apache license)
 * Apache Lucene, http://lucene.apache.org/
   (Apache license)
 * Fastutil, http://fastutil.di.unimi.it/
   (Apache license)
 * Koloboke, https://github.com/OpenHFT/Koloboke
   (Apache license)
 * Cliff Moon, https://github.com/moonpolysoft for a Robin Hood hashing pull request ( https://github.com/carrotsearch/hppc/pull/3 ) for HPPC.
    (Apache license)


                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS

   APPENDIX: How to apply the Apache License to your work.

      To apply the Apache License to your work, attach the following
      boilerplate notice, with the fields enclosed by brackets "[]"
      replaced with your own identifying information. (Don't include
      the brackets!)  The text should be enclosed in the appropriate
      comment syntax for the file format. We also recommend that a
      file or class name and description of purpose be included on the
      same "printed page" as the copyright notice for easier
      identification within third-party archives.

    Copyright 2013-2019, Vincent Sonnier (vsonnier@gmail.com)

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.lapissea.dfs.utils;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;


/**
 * A hash set of <code>int</code>s, implemented using open
 * addressing with linear probing for collision resolution.
 *
 * <p>
 * The internal buffers of this implementation ({@link #keys}, etc...)
 * are always allocated to the nearest size that is a power of two. When
 * the capacity exceeds the given load factor, the buffer size is doubled.
 * </p>
 */
public final class IntHashSet implements Cloneable, Iterable<IntHashSet.Cursor>{
	
	public static final class Cursor{
		/**
		 * The current value's index in the container this cursor belongs to. The meaning of
		 * this index is defined by the container (usually it will be an index in the underlying
		 * storage buffer).
		 */
		public int index;
		
		/**
		 * The current value.
		 */
		public int value;
		
		@Override
		public String toString(){
			return "[cursor, index: " + index + ", value: " + value + "]";
		}
	}
	
	/**
	 * Hash-indexed array holding all set entries.
	 * <p>
	 * Direct set iteration: iterate  {keys[i]} for i in [0; keys.length[ where keys[i] != 0/null, then also
	 * {0/null} is in the set if {@link #allocatedDefaultKey} = true.
	 * </p>
	 */
	public int[] keys;
	
	/**
	 * True if key = 0/null is in the map.
	 */
	public boolean allocatedDefaultKey = false;
	
	/**
	 * Cached number of assigned slots in {@link #keys}.
	 */
	private int assigned;
	
	/**
	 * The load factor for this map (fraction of allocated slots
	 * before the buffers must be rehashed or reallocated).
	 */
	private final double loadFactor;
	
	/**
	 * Resize buffers when {@link #keys} hits this value.
	 */
	private int resizeAt;
	
	/**
	 * Per-instance perturbation
	 * introduced in rehashing to create a unique key distribution.
	 */
	private final int perturbation = new Random().nextInt();
	
	
	public IntHashSet(){
		this(8, 0.75f);
	}
	
	public IntHashSet(final int initialCapacity){
		this(initialCapacity, 0.75f);
	}
	
	/**
	 * Creates a hash set with the given capacity and load factor.
	 */
	public IntHashSet(final int initialCapacity, final double loadFactor){
		this.loadFactor = loadFactor;
		//take into account of the load factor to guarantee no reallocations before reaching  initialCapacity.
		allocateBuffers(minBufferSize(initialCapacity, loadFactor));
	}
	
	private static int minBufferSize(final int elements, final double loadFactor){
		//Assure room for one additional slot (marking the not-allocated) + one more as safety margin.
		long length = (long)(elements/loadFactor) + 2;
		//Then, round it to the next power of 2.
		return (int)Math.max(1<<3, nextHighestPowerOfTwo(length));
	}
	private static long nextHighestPowerOfTwo(long v){
		v--;
		v |= v>>1;
		v |= v>>2;
		v |= v>>4;
		v |= v>>8;
		v |= v>>16;
		v |= v>>32;
		v++;
		return v;
	}
	private static int mix(final int k, final int seed){
		return mix32(k^seed);
	}
	private static int mix32(int k){
		k = (k^(k >>> 16))*0x85ebca6b;
		k = (k^(k >>> 13))*0xc2b2ae35;
		return k^(k >>> 16);
	}
	
	/**
	 * {@inheritDoc}
	 */
	public boolean add(int key){
		if(key == 0){
			if(allocatedDefaultKey){
				return false;
			}
			allocatedDefaultKey = true;
			return true;
		}
		
		final int mask = this.keys.length - 1;
		
		final int[] keys = this.keys;
		
		int slot = mix(key, this.perturbation)&mask;
		int existing;
		
		while(!((existing = keys[slot]) == 0)){
			if(key == existing) return false;
			
			slot = slot + 1&mask;
		}
		
		// Check if we need to grow. If so, reallocate new data,
		// fill in the last element and rehash.
		if(this.assigned == this.resizeAt){
			expandAndAdd(key, slot);
		}else{
			this.assigned++;
			keys[slot] = key;
		}
		return true;
	}
	public int addAll(IntHashSet container){
		return addAll((Iterable<? extends Cursor>)container);
	}
	
	/**
	 * Adds two elements to the set.
	 */
	public int add(final int e1, final int e2){
		int count = 0;
		if(add(e1)){
			count++;
		}
		if(add(e2)){
			count++;
		}
		return count;
	}
	
	/**
	 * Vararg-signature method for adding elements to this set.
	 * <p><b>This method is handy, but costly if used in tight loops (anonymous
	 * array passing)</b></p>
	 *
	 * @return Returns the number of elements that were added to the set
	 * (were not present in the set).
	 */
	public int add(final int... elements){
		int count = 0;
		for(final int e : elements){
			if(add(e)){
				count++;
			}
		}
		return count;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public int addAll(final Iterable<? extends Cursor> iterable){
		int count = 0;
		for(final Cursor cursor : iterable){
			if(add(cursor.value)){
				count++;
			}
		}
		return count;
	}
	
	/**
	 * Expand the internal storage buffers (capacity) or rehash current
	 * keys and values if there are a lot of deleted slots.
	 */
	private void expandAndAdd(final int pendingKey, final int freeSlot){
		assert this.assigned == this.resizeAt;
		
		//default sentinel value is never in the keys[] array, so never trigger reallocs
		assert !(pendingKey == 0);
		
		// Try to allocate new buffers first. If we OOM, it'll be now without
		// leaving the data structure in an inconsistent state.
		final int[] oldKeys = this.keys;
		
		allocateBuffers(this.keys.length*2);
		
		// We have succeeded at allocating new data so insert the pending key/value at
		// the free slot in the old arrays before rehashing.
		
		this.assigned++;
		
		oldKeys[freeSlot] = pendingKey;
		
		//Variables for adding
		final int mask = this.keys.length - 1;
		
		int         key, slot;
		final int[] keys = this.keys;
		
		//iterate all the old arrays to add in the newly allocated buffers
		//It is important to iterate backwards to minimize the conflict chain length !
		
		for(int i = oldKeys.length; --i>=0; ){
			//only consider non-empty slots, of course
			if(!((key = oldKeys[i]) == 0)){
				slot = mix(key, perturbation)&mask;
				
				//similar to add(), except all inserted keys are known to be unique.
				while(!((keys)[slot] == 0)){
					slot = slot + 1&mask;
				}
				
				//place it at that position
				keys[slot] = key;
			}
		}
	}
	
	/**
	 * Allocate internal buffers for a given capacity.
	 *
	 * @param capacity New capacity (must be a power of two).
	 */
	@SuppressWarnings("boxing")
	private void allocateBuffers(final int capacity){
		this.keys = new int[capacity];
		//allocate so that there is at least one slot that remains allocated = false
		//this is compulsory to guarantee proper stop in searching loops
		this.resizeAt = expandAtCount(capacity, this.loadFactor);
	}
	private static int expandAtCount(final int arraySize, final double loadFactor){
		// Take care of hash container invariant (there has to be at least one empty slot to ensure
		// the lookup loop finds either the element or an empty slot).
		return Math.min(arraySize - 1, (int)Math.ceil(arraySize*loadFactor));
	}
	
	public boolean remove(final int key){
		if(key == 0){
			if(this.allocatedDefaultKey){
				this.allocatedDefaultKey = false;
				return true;
			}
			return false;
		}
		
		final int mask = this.keys.length - 1;
		
		final int[] keys = this.keys;
		
		int slot = mix(key, this.perturbation)&mask;
		int existing;
		
		while(!((existing = keys[slot]) == 0)){
			if(key == existing){
				
				shiftConflictingKeys(slot);
				return true;
			}
			slot = slot + 1&mask;
		}
		
		return false;
	}
	
	/**
	 * Shift all the slot-conflicting keys allocated to (and including) <code>slot</code>.
	 */
	private void shiftConflictingKeys(int gapSlot){
		final int mask = this.keys.length - 1;
		
		final int[] keys = this.keys;
		
		// Perform shifts of conflicting keys to fill in the gap.
		int distance = 0;
		
		while(true){
			final int slot     = gapSlot + ++distance&mask;
			final int existing = keys[slot];
			
			if(existing == 0) break;
			
			final int idealSlotModMask = mix(existing, perturbation)&mask;
			
			//original HPPC code: shift = (slot - idealSlot) & mask;
			//equivalent to shift = (slot & mask - idealSlot & mask) & mask;
			//since slot and idealSlotModMask are already folded, we have :
			final int shift = slot - idealSlotModMask&mask;
			
			if(shift>=distance){
				// Entry at this position was originally at or before the gap slot.
				// Move the conflict-shifted entry to the gap's position and repeat the procedure
				// for any entries to the right of the current position, treating it
				// as the new gap.
				keys[gapSlot] = existing;
				
				gapSlot = slot;
				distance = 0;
			}
		}
		
		// Mark the last found gap slot without a conflict as empty.
		keys[gapSlot] = 0;
		
		this.assigned--;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public boolean contains(final int key){
		if(key == 0) return this.allocatedDefaultKey;
		
		final int mask = this.keys.length - 1;
		
		final int[] keys = this.keys;
		
		int slot = mix(key, this.perturbation)&mask;
		int existing;
		
		while(!((existing = keys[slot]) == 0)){
			if(key == existing){
				return true;
			}
			slot = slot + 1&mask;
		}
		
		return false;
	}
	
	public void clear(){
		this.assigned = 0;
		this.allocatedDefaultKey = false;
		var b = minBufferSize(8, loadFactor);
		if(keys.length != b) allocateBuffers(b);
		else Arrays.fill(keys, 0);
	}
	
	public int size(){
		return this.assigned + (this.allocatedDefaultKey? 1 : 0);
	}
	
	public int capacity(){
		return this.resizeAt;
	}
	
	@Override
	public int hashCode(){
		int h = 0;
		
		//allocated default key has hash = 0
		
		final int[] keys = this.keys;
		
		for(int i = keys.length; --i>=0; ){
			int existing;
			if(!((existing = keys[i]) == 0)){
				h += mix32(existing);
			}
		}
		
		return h;
	}
	
	@Override
	public boolean equals(final Object obj){
		if(obj == null) return false;
		if(obj == this) return true;
		if(!(obj instanceof IntHashSet other)) return false;
		
		//must be of the same size
		if(other.size() != this.size()){
			return false;
		}
		
		for(var cursor : this){
			if(!other.contains(cursor.value)){
				return false;
			}
		}
		return true;
	}
	
	/**
	 * An iterator implementation for {@link #iterator}.
	 * Holds a IntCursor returning (value, index) = (int value, index the position in {@link IntHashSet#keys}, or keys.length for key = 0/null.)
	 */
	public final class EntryIterator implements Iterator<Cursor>{
		private static final int NOT_CACHED = 0;
		private static final int CACHED     = 1;
		private static final int AT_END     = 2;
		
		public final Cursor cursor;
		/**
		 * Current iterator state.
		 */
		private      int    state = NOT_CACHED;
		/**
		 * The next element to be returned from {@link #next()} if
		 * fetched.
		 */
		private      Cursor nextElement;
		
		public EntryIterator(){
			cursor = new Cursor();
			cursor.index = IntHashSet.this.keys.length + 1;
		}
		
		/**
		 * Iterate backwards w.r.t the buffer, to
		 * minimize collision chains when filling another hash container (ex. with putAll())
		 */
		private Cursor fetch(){
			if(this.cursor.index == IntHashSet.this.keys.length + 1){
				if(IntHashSet.this.allocatedDefaultKey){
					this.cursor.index = IntHashSet.this.keys.length;
					this.cursor.value = 0;
					
					return this.cursor;
				}
				//no value associated with the default key, continue iteration...
				this.cursor.index = IntHashSet.this.keys.length;
			}
			
			int i = this.cursor.index - 1;
			
			while(i>=0 && IntHashSet.this.keys[i] == 0){
				i--;
			}
			
			if(i == -1){
				return done();
			}
			
			this.cursor.index = i;
			this.cursor.value = IntHashSet.this.keys[i];
			return this.cursor;
		}
		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean hasNext(){
			if(this.state == NOT_CACHED){
				this.state = CACHED;
				this.nextElement = fetch();
			}
			
			return (this.state == CACHED);
		}
		/**
		 * {@inheritDoc}
		 */
		@Override
		public Cursor next(){
			if(!hasNext()){
				throw new NoSuchElementException();
			}
			
			state = NOT_CACHED;
			return nextElement;
		}
		/**
		 * Default implementation throws {@link UnsupportedOperationException}.
		 */
		@Override
		public void remove(){
			throw new UnsupportedOperationException();
		}
		/**
		 * Call when done.
		 */
		private Cursor done(){
			this.state = AT_END;
			return null;
		}
	}
	
	@Override
	public EntryIterator iterator(){
		return new EntryIterator();
	}
	
	public void forEach(final IntConsumer procedure){
		if(allocatedDefaultKey){
			procedure.accept(0);
		}
		
		final int[] keys = this.keys;
		
		//Iterate in reverse for side-stepping the longest conflict chain
		//in another hash, in case apply() is actually used to fill another hash container.
		for(int i = keys.length - 1; i>=0; i--){
			int existing;
			if(!((existing = keys[i]) == 0)){
				procedure.accept(existing);
			}
		}
	}
	
	public int[] toArray(final int[] target){
		int count = 0;
		
		if(this.allocatedDefaultKey){
			target[count++] = 0;
		}
		
		for(int key : this.keys){
			int existing;
			if(!((existing = key) == 0)){
				target[count++] = existing;
			}
		}
		
		assert count == this.size();
		
		return target;
	}
	
	@Override
	public IntHashSet clone(){
		//clone to size() to prevent eventual exponential growth
		var cloned = new IntHashSet(this.size(), this.loadFactor);
		
		//We must NOT clone, because of the independent perturbation seeds
		cloned.addAll(this);
		
		return cloned;
	}
	
	public <T extends IntPredicate> T forEach(final T predicate){
		if(this.allocatedDefaultKey){
			if(!predicate.test(0)){
				return predicate;
			}
		}
		
		final int[] keys = this.keys;
		
		//Iterate in reverse for side-stepping the longest conflict chain
		//in another hash, in case apply() is actually used to fill another hash container.
		for(int i = keys.length - 1; i>=0; i--){
			int existing;
			if(!((existing = keys[i]) == 0)){
				if(!predicate.test(existing)){
					break;
				}
			}
		}
		
		return predicate;
	}
	
	/**
	 * {@inheritDoc}
	 */
	public int removeAll(final IntPredicate predicate){
		final int before = this.size();
		if(this.allocatedDefaultKey){
			if(predicate.test(0)){
				this.allocatedDefaultKey = false;
			}
		}
		
		final int[] keys = this.keys;
		for(int i = 0; i<keys.length; ){
			int existing;
			if(!((existing = keys[i]) == 0) && predicate.test(existing)){
				shiftConflictingKeys(i);
				// Shift, do not increment slot.
			}else{
				i++;
			}
		}
		
		return before - this.size();
	}
	
	/**
	 * Create a set from a variable number of arguments or an array of <code>int</code>.
	 */
	public static IntHashSet of(final int... elements){
		final IntHashSet set = new IntHashSet(elements.length);
		set.add(elements);
		return set;
	}
	
	public int retainAll(final IntPredicate predicate){
		return this.removeAll(k -> !predicate.test(k));
	}
	public int[] toArray(){
		return toArray(new int[size()]);
	}
	/**
	 * Convert the contents of this container to a human-friendly string.
	 */
	@Override
	public String toString(){
		return Arrays.toString(this.toArray());
	}
	public boolean isEmpty(){
		return size() == 0;
	}
}
