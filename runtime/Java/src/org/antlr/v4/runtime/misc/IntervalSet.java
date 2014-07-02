/*
 * [The "BSD license"]
 *  Copyright (c) 2012 Terence Parr
 *  Copyright (c) 2012 Sam Harwell
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.antlr.v4.runtime.misc;

import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

/**
 * This class implements the {@link IntSet} backed by a sorted array of
 * non-overlapping intervals. It is particularly efficient for representing
 * large collections of numbers, where the majority of elements appear as part
 * of a sequential range of numbers that are all part of the set. For example,
 * the set { 1, 2, 3, 4, 7, 8 } may be represented as { [1, 4], [7, 8] }.
 *
 * <p>
 * This class is able to represent sets containing any combination of values in
 * the range {@link Integer#MIN_VALUE} to {@link Integer#MAX_VALUE}
 * (inclusive).</p>
 */
public class IntervalSet implements IntSet {
	public static final IntervalSet COMPLETE_CHAR_SET = IntervalSet.of(Lexer.MIN_CHAR_VALUE, Lexer.MAX_CHAR_VALUE);
	static {
		COMPLETE_CHAR_SET.setReadonly(true);
	}

	public static final IntervalSet EMPTY_SET = new IntervalSet();
	static {
		EMPTY_SET.setReadonly(true);
	}

	/** The list of sorted, disjoint intervals. */
    protected List<Interval> intervals;

    protected boolean readonly;

	public IntervalSet(List<Interval> intervals) {
		this.intervals = intervals;
	}

	public IntervalSet(IntervalSet set) {
		this();
		addAll(set);
	}

	public IntervalSet(int... els) {
		if ( els==null ) {
			intervals = new ArrayList<Interval>(2); // most sets are 1 or 2 elements
		}
		else {
			intervals = new ArrayList<Interval>(els.length);
			for (int e : els) add(e);
		}
	}

	/** Create a set with a single element, el. */
    @NotNull
    public static IntervalSet of(int a) {
		IntervalSet s = new IntervalSet();
        s.add(a);
        return s;
    }

    /** Create a set with all ints within range [a..b] (inclusive) */
	public static IntervalSet of(int a, int b) {
		IntervalSet s = new IntervalSet();
		s.add(a,b);
		return s;
	}

	public void clear() {
        if ( readonly ) throw new IllegalStateException("can't alter readonly IntervalSet");
		intervals.clear();
	}

    /** Add a single element to the set.  An isolated element is stored
     *  as a range el..el.
     */
    @Override
    public void add(int el) {
        if ( readonly ) throw new IllegalStateException("can't alter readonly IntervalSet");
        add(el,el);
    }

    /** Add interval; i.e., add all integers from a to b to set.
     *  If b&lt;a, do nothing.
     *  Keep list in sorted order (by left range value).
     *  If overlap, combine ranges.  For example,
     *  If this is {1..5, 10..20}, adding 6..7 yields
     *  {1..5, 6..7, 10..20}.  Adding 4..8 yields {1..8, 10..20}.
     */
    public void add(int a, int b) {
        add(Interval.of(a, b));
    }

	// copy on write so we can cache a..a intervals and sets of that
	protected void add(Interval addition) {
        if ( readonly ) throw new IllegalStateException("can't alter readonly IntervalSet");
		//System.out.println("add "+addition+" to "+intervals.toString());
		if ( addition.b<addition.a ) {
			return;
		}
		// find position in list
		// Use iterators as we modify list in place
		for (ListIterator<Interval> iter = intervals.listIterator(); iter.hasNext();) {
			Interval r = iter.next();
			if ( addition.equals(r) ) {
				return;
			}
			if ( addition.adjacent(r) || !addition.disjoint(r) ) {
				// next to each other, make a single larger interval
				Interval bigger = addition.union(r);
				iter.set(bigger);
				// make sure we didn't just create an interval that
				// should be merged with next interval in list
				while ( iter.hasNext() ) {
					Interval next = iter.next();
					if ( !bigger.adjacent(next) && bigger.disjoint(next) ) {
						break;
					}

					// if we bump up against or overlap next, merge
					iter.remove();   // remove this one
					iter.previous(); // move backwards to what we just set
					iter.set(bigger.union(next)); // set to 3 merged ones
					iter.next(); // first call to next after previous duplicates the result
				}
				return;
			}
			if ( addition.startsBeforeDisjoint(r) ) {
				// insert before r
				iter.previous();
				iter.add(addition);
				return;
			}
			// if disjoint and after r, a future iteration will handle it
		}
		// ok, must be after last interval (and disjoint from last interval)
		// just add it
		intervals.add(addition);
	}

	/** combine all sets in the array returned the or'd value */
	public static IntervalSet or(IntervalSet[] sets) {
		IntervalSet r = new IntervalSet();
		for (IntervalSet s : sets) r.addAll(s);
		return r;
	}

	@Override
	public IntervalSet addAll(IntSet set) {
		if ( set==null ) {
			return this;
		}

		if (set instanceof IntervalSet) {
			IntervalSet other = (IntervalSet)set;
			// walk set and add each interval
			int n = other.intervals.size();
			for (int i = 0; i < n; i++) {
				Interval I = other.intervals.get(i);
				this.add(I.a,I.b);
			}
		}
		else {
			for (int value : set.toList()) {
				add(value);
			}
		}

		return this;
    }

    public IntervalSet complement(int minElement, int maxElement) {
        return this.complement(IntervalSet.of(minElement,maxElement));
    }

    /** {@inheritDoc} */
    @Override
    public IntervalSet complement(IntSet vocabulary) {
		if ( vocabulary==null || vocabulary.isNil() ) {
			return null; // nothing in common with null set
		}

		IntervalSet vocabularyIS;
		if (vocabulary instanceof IntervalSet) {
			vocabularyIS = (IntervalSet)vocabulary;
		}
		else {
			vocabularyIS = new IntervalSet();
			vocabularyIS.addAll(vocabulary);
		}

		return vocabularyIS.subtract(this);
    }

	@Override
	public IntervalSet subtract(IntSet a) {
		if (a == null || a.isNil()) {
			return new IntervalSet(this);
		}

		if (a instanceof IntervalSet) {
			return subtract(this, (IntervalSet)a);
		}

		IntervalSet other = new IntervalSet();
		other.addAll(a);
		return subtract(this, other);
	}

	/**
	 * Compute the set difference between two interval sets. The specific
	 * operation is {@code left - right}. If either of the input sets is
	 * {@code null}, it is treated as though it was an empty set.
	 */
	@NotNull
	public static IntervalSet subtract(@Nullable IntervalSet left, @Nullable IntervalSet right) {
		if (left == null || left.isNil()) {
			return new IntervalSet();
		}

		IntervalSet result = new IntervalSet(left);
		if (right == null || right.isNil()) {
			// right set has no elements; just return the copy of the current set
			return result;
		}

		int resultI = 0;
		int rightI = 0;
		while (resultI < result.intervals.size() && rightI < right.intervals.size()) {
			Interval resultInterval = result.intervals.get(resultI);
			Interval rightInterval = right.intervals.get(rightI);

			// operation: (resultInterval - rightInterval) and update indexes

			if (rightInterval.b < resultInterval.a) {
				rightI++;
				continue;
			}

			if (rightInterval.a > resultInterval.b) {
				resultI++;
				continue;
			}

			Interval beforeCurrent = null;
			Interval afterCurrent = null;
			if (rightInterval.a > resultInterval.a) {
				beforeCurrent = new Interval(resultInterval.a, rightInterval.a - 1);
			}

			if (rightInterval.b < resultInterval.b) {
				afterCurrent = new Interval(rightInterval.b + 1, resultInterval.b);
			}

			if (beforeCurrent != null) {
				if (afterCurrent != null) {
					// split the current interval into two
					result.intervals.set(resultI, beforeCurrent);
					result.intervals.add(resultI + 1, afterCurrent);
					resultI++;
					rightI++;
					continue;
				}
				else {
					// replace the current interval
					result.intervals.set(resultI, beforeCurrent);
					resultI++;
					continue;
				}
			}
			else {
				if (afterCurrent != null) {
					// replace the current interval
					result.intervals.set(resultI, afterCurrent);
					rightI++;
					continue;
				}
				else {
					// remove the current interval (thus no need to increment resultI)
					result.intervals.remove(resultI);
					continue;
				}
			}
		}

		// If rightI reached right.intervals.size(), no more intervals to subtract from result.
		// If resultI reached result.intervals.size(), we would be subtracting from an empty set.
		// Either way, we are done.
		return result;
	}

	@Override
	public IntervalSet or(IntSet a) {
		IntervalSet o = new IntervalSet();
		o.addAll(this);
		o.addAll(a);
		return o;
	}

    /** {@inheritDoc} */
	@Override
	public IntervalSet and(IntSet other) {
		if ( other==null ) { //|| !(other instanceof IntervalSet) ) {
			return null; // nothing in common with null set
		}

		List<Interval> myIntervals = this.intervals;
		List<Interval> theirIntervals = ((IntervalSet)other).intervals;
		IntervalSet intersection = null;
		int mySize = myIntervals.size();
		int theirSize = theirIntervals.size();
		int i = 0;
		int j = 0;
		// iterate down both interval lists looking for nondisjoint intervals
		while ( i<mySize && j<theirSize ) {
			Interval mine = myIntervals.get(i);
			Interval theirs = theirIntervals.get(j);
			//System.out.println("mine="+mine+" and theirs="+theirs);
			if ( mine.startsBeforeDisjoint(theirs) ) {
				// move this iterator looking for interval that might overlap
				i++;
			}
			else if ( theirs.startsBeforeDisjoint(mine) ) {
				// move other iterator looking for interval that might overlap
				j++;
			}
			else if ( mine.properlyContains(theirs) ) {
				// overlap, add intersection, get next theirs
				if ( intersection==null ) {
					intersection = new IntervalSet();
				}
				intersection.add(mine.intersection(theirs));
				j++;
			}
			else if ( theirs.properlyContains(mine) ) {
				// overlap, add intersection, get next mine
				if ( intersection==null ) {
					intersection = new IntervalSet();
				}
				intersection.add(mine.intersection(theirs));
				i++;
			}
			else if ( !mine.disjoint(theirs) ) {
				// overlap, add intersection
				if ( intersection==null ) {
					intersection = new IntervalSet();
				}
				intersection.add(mine.intersection(theirs));
				// Move the iterator of lower range [a..b], but not
				// the upper range as it may contain elements that will collide
				// with the next iterator. So, if mine=[0..115] and
				// theirs=[115..200], then intersection is 115 and move mine
				// but not theirs as theirs may collide with the next range
				// in thisIter.
				// move both iterators to next ranges
				if ( mine.startsAfterNonDisjoint(theirs) ) {
					j++;
				}
				else if ( theirs.startsAfterNonDisjoint(mine) ) {
					i++;
				}
			}
		}
		if ( intersection==null ) {
			return new IntervalSet();
		}
		return intersection;
	}

    /** {@inheritDoc} */
    @Override
    public boolean contains(int el) {
		int n = intervals.size();
		for (int i = 0; i < n; i++) {
			Interval I = intervals.get(i);
			int a = I.a;
			int b = I.b;
			if ( el<a ) {
				break; // list is sorted and el is before this interval; not here
			}
			if ( el>=a && el<=b ) {
				return true; // found in this interval
			}
		}
		return false;
/*
		for (ListIterator iter = intervals.listIterator(); iter.hasNext();) {
            Interval I = (Interval) iter.next();
            if ( el<I.a ) {
                break; // list is sorted and el is before this interval; not here
            }
            if ( el>=I.a && el<=I.b ) {
                return true; // found in this interval
            }
        }
        return false;
        */
    }

    /** {@inheritDoc} */
    @Override
    public boolean isNil() {
        return intervals==null || intervals.isEmpty();
    }

    /** {@inheritDoc} */
    @Override
    public int getSingleElement() {
        if ( intervals!=null && intervals.size()==1 ) {
            Interval I = intervals.get(0);
            if ( I.a == I.b ) {
                return I.a;
            }
        }
        return Token.INVALID_TYPE;
    }

	/**
	 * Returns the maximum value contained in the set.
	 *
	 * @return the maximum value contained in the set. If the set is empty, this
	 * method returns {@link Token#INVALID_TYPE}.
	 *
	 * @sharpen.property MaxElement
	 */
	public int getMaxElement() {
		if ( isNil() ) {
			return Token.INVALID_TYPE;
		}
		Interval last = intervals.get(intervals.size()-1);
		return last.b;
	}

	/**
	 * Returns the minimum value contained in the set.
	 *
	 * @return the minimum value contained in the set. If the set is empty, this
	 * method returns {@link Token#INVALID_TYPE}.
	 *
	 * @sharpen.property MinElement
	 */
	public int getMinElement() {
		if ( isNil() ) {
			return Token.INVALID_TYPE;
		}

		return intervals.get(0).a;
	}

    /** Return a list of Interval objects. */
    public List<Interval> getIntervals() {
        return intervals;
    }

	@Override
	public int hashCode() {
		int hash = MurmurHash.initialize();
		for (Interval I : intervals) {
			hash = MurmurHash.update(hash, I.a);
			hash = MurmurHash.update(hash, I.b);
		}

		hash = MurmurHash.finish(hash, intervals.size() * 2);
		return hash;
	}

	/** Are two IntervalSets equal?  Because all intervals are sorted
     *  and disjoint, equals is a simple linear walk over both lists
     *  to make sure they are the same.  Interval.equals() is used
     *  by the List.equals() method to check the ranges.
     */
    @Override
    public boolean equals(Object obj) {
        if ( obj==null || !(obj instanceof IntervalSet) ) {
            return false;
        }
        IntervalSet other = (IntervalSet)obj;
		return this.intervals.equals(other.intervals);
	}

	@Override
	public String toString() { return toString(false); }

	public String toString(boolean elemAreChar) {
		StringBuilder buf = new StringBuilder();
		if ( this.intervals==null || this.intervals.isEmpty() ) {
			return "{}";
		}
		if ( this.size()>1 ) {
			buf.append("{");
		}
		Iterator<Interval> iter = this.intervals.iterator();
		while (iter.hasNext()) {
			Interval I = iter.next();
			int a = I.a;
			int b = I.b;
			if ( a==b ) {
				if ( a==Token.EOF ) buf.append("<EOF>");
				else if ( elemAreChar ) buf.append("'").append((char)a).append("'");
				else buf.append(a);
			}
			else {
				if ( elemAreChar ) buf.append("'").append((char)a).append("'..'").append((char)b).append("'");
				else buf.append(a).append("..").append(b);
			}
			if ( iter.hasNext() ) {
				buf.append(", ");
			}
		}
		if ( this.size()>1 ) {
			buf.append("}");
		}
		return buf.toString();
	}

	public String toString(String[] tokenNames) {
		StringBuilder buf = new StringBuilder();
		if ( this.intervals==null || this.intervals.isEmpty() ) {
			return "{}";
		}
		if ( this.size()>1 ) {
			buf.append("{");
		}
		Iterator<Interval> iter = this.intervals.iterator();
		while (iter.hasNext()) {
			Interval I = iter.next();
			int a = I.a;
			int b = I.b;
			if ( a==b ) {
				buf.append(elementName(tokenNames, a));
			}
			else {
				for (int i=a; i<=b; i++) {
					if ( i>a ) buf.append(", ");
                    buf.append(elementName(tokenNames, i));
				}
			}
			if ( iter.hasNext() ) {
				buf.append(", ");
			}
		}
		if ( this.size()>1 ) {
			buf.append("}");
		}
        return buf.toString();
    }

    protected String elementName(String[] tokenNames, int a) {
        if ( a==Token.EOF ) return "<EOF>";
        else if ( a==Token.EPSILON ) return "<EPSILON>";
        else return tokenNames[a];

    }

    @Override
    public int size() {
		int n = 0;
		int numIntervals = intervals.size();
		if ( numIntervals==1 ) {
			Interval firstInterval = this.intervals.get(0);
			return firstInterval.b-firstInterval.a+1;
		}
		for (int i = 0; i < numIntervals; i++) {
			Interval I = intervals.get(i);
			n += (I.b-I.a+1);
		}
		return n;
    }

	public IntegerList toIntegerList() {
		IntegerList values = new IntegerList(size());
		int n = intervals.size();
		for (int i = 0; i < n; i++) {
			Interval I = intervals.get(i);
			int a = I.a;
			int b = I.b;
			for (int v=a; v<=b; v++) {
				values.add(v);
			}
		}
		return values;
	}

    @Override
    public List<Integer> toList() {
		List<Integer> values = new ArrayList<Integer>();
		int n = intervals.size();
		for (int i = 0; i < n; i++) {
			Interval I = intervals.get(i);
			int a = I.a;
			int b = I.b;
			for (int v=a; v<=b; v++) {
				values.add(v);
			}
		}
		return values;
	}

	public Set<Integer> toSet() {
		Set<Integer> s = new HashSet<Integer>();
		for (Interval I : intervals) {
			int a = I.a;
			int b = I.b;
			for (int v=a; v<=b; v++) {
				s.add(v);
			}
		}
		return s;
	}

	public int[] toArray() {
		return toIntegerList().toArray();
	}

	@Override
	public void remove(int el) {
        if ( readonly ) throw new IllegalStateException("can't alter readonly IntervalSet");
        int n = intervals.size();
        for (int i = 0; i < n; i++) {
            Interval I = intervals.get(i);
            int a = I.a;
            int b = I.b;
            if ( el<a ) {
                break; // list is sorted and el is before this interval; not here
            }
            // if whole interval x..x, rm
            if ( el==a && el==b ) {
                intervals.remove(i);
                break;
            }
            // if on left edge x..b, adjust left
            if ( el==a ) {
                intervals.set(i, Interval.of(I.a + 1, I.b));
                break;
            }
            // if on right edge a..x, adjust right
            if ( el==b ) {
                intervals.set(i, Interval.of(I.a, I.b - 1));
                break;
            }
            // if in middle a..x..b, split interval
            if ( el>a && el<b ) { // found in this interval
                int oldb = I.b;
                intervals.set(i, Interval.of(I.a, el - 1)); // [a..x-1]
                add(el+1, oldb); // add [x+1..b]
            }
        }
    }

	/**
	 * @sharpen.property IsReadOnly
	 */
    public boolean isReadonly() {
        return readonly;
    }

    public void setReadonly(boolean readonly) {
        if ( this.readonly && !readonly ) throw new IllegalStateException("can't alter readonly IntervalSet");
        this.readonly = readonly;
    }
}
