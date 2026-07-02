/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.moviemaker.imported;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.LoaderCache;

/**
 * Copied verbatim from {@code org.janelia.saalfeldlab.hotknife.util.RobustSoftRefLoaderCache}.
 * A soft-reference loader cache that fixes concurrency issues when calling
 * invalidateAll() while BDV is drawing.
 */
public class RobustSoftRefLoaderCache<K, V> implements LoaderCache< K, V >
{
	final ConcurrentHashMap< K, Entry > map = new ConcurrentHashMap<>();

	final ReferenceQueue< V > queue = new ReferenceQueue<>();

	static final class CacheSoftReference< V > extends SoftReference< V >
	{
		private final RobustSoftRefLoaderCache< ?, V >.Entry entry;

		public CacheSoftReference()
		{
			super( null );
			this.entry = null;
		}

		public CacheSoftReference( final V referent, final ReferenceQueue< V > remove, final RobustSoftRefLoaderCache< ?, V >.Entry entry )
		{
			super( referent, remove );
			this.entry = entry;
		}
	}

	final class Entry
	{
		final K key;

		private CacheSoftReference< V > ref;

		boolean loaded;

		public Entry( final K key )
		{
			this.key = key;
			this.ref = new CacheSoftReference<>();
			this.loaded = false;
		}

		public V getValue()
		{
			final CacheSoftReference< V > myRef = ref;
			if ( myRef == null )
				return null;
			else
				return myRef.get();
		}

		public void setValue( final V value )
		{
			this.loaded = true;
			this.ref = new CacheSoftReference<>( value, queue, this );
		}

		public void remove()
		{
			map.remove( key, this );
		}
	}

	@Override
	public V getIfPresent( final K key )
	{
		cleanUp();
		final Entry entry = map.get( key );
		return entry == null ? null : entry.getValue();
	}

	@Override
	public V get( final K key, final CacheLoader< ? super K, ? extends V > loader ) throws ExecutionException
	{
		cleanUp();
		final Entry entry = map.computeIfAbsent( key, ( k ) -> new Entry( k ) );
		V value = entry.getValue();
		if ( value == null )
		{
			synchronized ( entry )
			{
				if ( entry.loaded )
				{
					value = entry.getValue();
					if ( value == null )
					{
						/*
						 * The entry was already loaded, but its value has been
						 * garbage collected. We need to create a new entry
						 */
						entry.remove();
						value = get( key, loader );
					}
				}
				else
				{
					try
					{
						value = loader.get( key );
						entry.setValue( value );
					}
					catch ( final InterruptedException e )
					{
						Thread.currentThread().interrupt();
						throw new ExecutionException( e );
					}
					catch ( final Exception e )
					{
						throw new ExecutionException( e );
					}
				}
			}
		}
		return value;
	}

	@Override
	public void persist( final K key )
	{}

	@Override
	public void persistIf( final Predicate< K > condition )
	{}

	@Override
	public void persistAll()
	{}

	@Override
	public void invalidate( final K key )
	{
		final Entry entry = map.remove( key );
		if ( entry != null )
		{
			final CacheSoftReference< V > ref = entry.ref;
			if ( ref != null )
				ref.clear();
			entry.ref = null;
		}
	}

	@Override
	public void invalidateIf( final long parallelismThreshold, final Predicate< K > condition )
	{
		map.forEachValue( parallelismThreshold, entry ->
		{
			if ( condition.test( entry.key ) )
			{
				entry.remove();
				final CacheSoftReference< V > ref = entry.ref;
				if ( ref != null )
					ref.clear();
				entry.ref = null;
			}
		} );
	}

	@Override
	public void invalidateAll( final long parallelismThreshold )
	{
		map.forEachValue( parallelismThreshold, entry ->
		{
			entry.remove();
			final CacheSoftReference< V > ref = entry.ref;
			if ( ref != null )
				ref.clear();
			entry.ref = null;
		} );
	}

	/**
	 * Remove entries from the cache whose references have been
	 * garbage-collected.
	 */
	public void cleanUp()
	{
		while ( true )
		{
			@SuppressWarnings( "unchecked" )
			final CacheSoftReference< V > poll = ( CacheSoftReference< V > ) queue.poll();
			if ( poll == null )
				break;
			poll.entry.remove();
		}
	}
}
