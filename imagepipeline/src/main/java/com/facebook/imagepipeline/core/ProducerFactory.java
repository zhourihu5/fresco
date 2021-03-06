/*
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.imagepipeline.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Pair;

import com.facebook.cache.common.CacheKey;
import com.facebook.common.references.CloseableReference;
import com.facebook.imagepipeline.cache.BitmapMemoryCacheKey;
import com.facebook.imagepipeline.cache.BufferedDiskCache;
import com.facebook.imagepipeline.cache.CacheKeyFactory;
import com.facebook.imagepipeline.cache.MemoryCache;
import com.facebook.imagepipeline.decoder.CloseableImageCopier;
import com.facebook.imagepipeline.decoder.ImageDecoder;
import com.facebook.imagepipeline.decoder.ProgressiveJpegConfig;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.memory.ByteArrayPool;
import com.facebook.imagepipeline.memory.PooledByteBuffer;
import com.facebook.imagepipeline.memory.PooledByteBufferFactory;
import com.facebook.imagepipeline.producers.AddImageTransformMetaDataProducer;
import com.facebook.imagepipeline.producers.BitmapMemoryCacheGetProducer;
import com.facebook.imagepipeline.producers.BitmapMemoryCacheKeyMultiplexProducer;
import com.facebook.imagepipeline.producers.BitmapMemoryCacheProducer;
import com.facebook.imagepipeline.producers.BranchOnSeparateImagesProducer;
import com.facebook.imagepipeline.producers.DecodeProducer;
import com.facebook.imagepipeline.producers.DiskCacheProducer;
import com.facebook.imagepipeline.producers.EncodedCacheKeyMultiplexProducer;
import com.facebook.imagepipeline.producers.EncodedMemoryCacheProducer;
import com.facebook.imagepipeline.producers.ImageTransformMetaData;
import com.facebook.imagepipeline.producers.LocalAssetFetchProducer;
import com.facebook.imagepipeline.producers.LocalContentUriFetchProducer;
import com.facebook.imagepipeline.producers.LocalExifThumbnailProducer;
import com.facebook.imagepipeline.producers.LocalFileFetchProducer;
import com.facebook.imagepipeline.producers.LocalResourceFetchProducer;
import com.facebook.imagepipeline.producers.LocalVideoThumbnailProducer;
import com.facebook.imagepipeline.producers.NetworkFetchProducer;
import com.facebook.imagepipeline.producers.NetworkFetcher;
import com.facebook.imagepipeline.producers.NullProducer;
import com.facebook.imagepipeline.producers.PostprocessorProducer;
import com.facebook.imagepipeline.producers.Producer;
import com.facebook.imagepipeline.producers.RemoveImageTransformMetaDataProducer;
import com.facebook.imagepipeline.producers.ResizeAndRotateProducer;
import com.facebook.imagepipeline.producers.SwallowResultProducer;
import com.facebook.imagepipeline.producers.ThreadHandoffProducer;
import com.facebook.imagepipeline.producers.ThrottlingProducer;
import com.facebook.imagepipeline.producers.WebpTranscodeProducer;

public class ProducerFactory {
  // Local dependencies
  private ContentResolver mContentResolver;
  private Resources mResources;
  private AssetManager mAssetManager;

  // Decode dependencies
  private final ByteArrayPool mByteArrayPool;
  private final ImageDecoder mImageDecoder;
  private final ProgressiveJpegConfig mProgressiveJpegConfig;

  // Dependencies used by multiple steps
  private final ExecutorSupplier mExecutorSupplier;
  private final PooledByteBufferFactory mPooledByteBufferFactory;

  // Cache dependencies
  private final BufferedDiskCache mDefaultBufferedDiskCache;
  private final BufferedDiskCache mSmallImageBufferedDiskCache;
  private final MemoryCache<CacheKey, PooledByteBuffer, Void> mEncodedMemoryCache;
  private final MemoryCache<BitmapMemoryCacheKey, CloseableImage, Void> mBitmapMemoryCache;
  private final CacheKeyFactory mCacheKeyFactory;

  // Postproc dependencies
  private final CloseableImageCopier mCloseableImageCopier;

  public ProducerFactory(
      Context context,
      ByteArrayPool byteArrayPool,
      ImageDecoder imageDecoder,
      ProgressiveJpegConfig progressiveJpegConfig,
      ExecutorSupplier executorSupplier,
      PooledByteBufferFactory pooledByteBufferFactory,
      MemoryCache<BitmapMemoryCacheKey, CloseableImage, Void> bitmapMemoryCache,
      MemoryCache<CacheKey, PooledByteBuffer, Void> encodedMemoryCache,
      BufferedDiskCache defaultBufferedDiskCache,
      BufferedDiskCache smallImageBufferedDiskCache,
      CacheKeyFactory cacheKeyFactory,
      CloseableImageCopier closeableImageCopier) {
    mContentResolver = context.getApplicationContext().getContentResolver();
    mResources = context.getApplicationContext().getResources();
    mAssetManager = context.getApplicationContext().getAssets();

    mByteArrayPool = byteArrayPool;
    mImageDecoder = imageDecoder;
    mProgressiveJpegConfig = progressiveJpegConfig;

    mExecutorSupplier = executorSupplier;
    mPooledByteBufferFactory = pooledByteBufferFactory;

    mBitmapMemoryCache = bitmapMemoryCache;
    mEncodedMemoryCache = encodedMemoryCache;
    mDefaultBufferedDiskCache = defaultBufferedDiskCache;
    mSmallImageBufferedDiskCache = smallImageBufferedDiskCache;
    mCacheKeyFactory = cacheKeyFactory;

    mCloseableImageCopier = closeableImageCopier;
  }

  public static AddImageTransformMetaDataProducer newAddImageTransformMetaDataProducer(
      Producer<CloseableReference<PooledByteBuffer>> nextProducer) {
    return new AddImageTransformMetaDataProducer(nextProducer);
  }

  public BitmapMemoryCacheGetProducer newBitmapMemoryCacheGetProducer(
      Producer<CloseableReference<CloseableImage>> nextProducer) {
    return new BitmapMemoryCacheGetProducer(mBitmapMemoryCache, mCacheKeyFactory, nextProducer);
  }

  public BitmapMemoryCacheKeyMultiplexProducer newBitmapMemoryCacheKeyMultiplexProducer(
      Producer<CloseableReference<CloseableImage>> nextProducer) {
    return new BitmapMemoryCacheKeyMultiplexProducer(mCacheKeyFactory, nextProducer);
  }

  public BitmapMemoryCacheProducer newBitmapMemoryCacheProducer(
      Producer<CloseableReference<CloseableImage>> nextProducer) {
    return new BitmapMemoryCacheProducer(mBitmapMemoryCache, mCacheKeyFactory, nextProducer);
  }

  public static BranchOnSeparateImagesProducer newBranchOnSeparateImagesProducer(
      Producer<Pair<CloseableReference<PooledByteBuffer>, ImageTransformMetaData>> nextProducer1,
      Producer<Pair<CloseableReference<PooledByteBuffer>, ImageTransformMetaData>> nextProducer2) {
    return new BranchOnSeparateImagesProducer(nextProducer1, nextProducer2);
  }

  public DecodeProducer newDecodeProducer(
      Producer<CloseableReference<PooledByteBuffer>> nextProducer) {
    return new DecodeProducer(
        mByteArrayPool,
        mExecutorSupplier.forDecode(),
        mImageDecoder,
        mProgressiveJpegConfig,
        nextProducer);
  }

  public DiskCacheProducer newDiskCacheProducer(
      Producer<CloseableReference<PooledByteBuffer>> nextProducer) {
    return new DiskCacheProducer(
        mDefaultBufferedDiskCache,
        mSmallImageBufferedDiskCache,
        mCacheKeyFactory,
        nextProducer);
  }

  public EncodedCacheKeyMultiplexProducer newEncodedCacheKeyMultiplexProducer(
      Producer<CloseableReference<PooledByteBuffer>> nextProducer) {
    return new EncodedCacheKeyMultiplexProducer(mCacheKeyFactory, nextProducer);
  }

  public EncodedMemoryCacheProducer newEncodedMemoryCacheProducer(
      Producer<CloseableReference<PooledByteBuffer>> nextProducer) {
    return new EncodedMemoryCacheProducer(mEncodedMemoryCache, mCacheKeyFactory, nextProducer);
  }

  public LocalAssetFetchProducer newLocalAssetFetchProducer() {
    return new LocalAssetFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mAssetManager);
  }

  public LocalContentUriFetchProducer newContentUriFetchProducer() {
    return new LocalContentUriFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mContentResolver);
  }

  public LocalExifThumbnailProducer newLocalExifThumbnailProducer() {
    return new LocalExifThumbnailProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory);
  }

  public LocalFileFetchProducer newLocalFileFetchProducer() {
    return new LocalFileFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory);
  }

  public LocalResourceFetchProducer newLocalResourceFetchProducer() {
    return new LocalResourceFetchProducer(
        mExecutorSupplier.forLocalStorageRead(),
        mPooledByteBufferFactory,
        mResources);
  }

  public LocalVideoThumbnailProducer newLocalVideoThumbnailProducer() {
    return new LocalVideoThumbnailProducer(mExecutorSupplier.forLocalStorageRead());
  }

  public NetworkFetchProducer newNetworkFetchProducer(NetworkFetcher networkFetcher) {
    return new NetworkFetchProducer(mPooledByteBufferFactory, mByteArrayPool, networkFetcher);
  }

  public static <T> NullProducer<T> newNullProducer() {
    return new NullProducer<T>();
  }

  public PostprocessorProducer newPostprocessorProducer(
      Producer<CloseableReference<CloseableImage>> nextProducer) {
    return new PostprocessorProducer(
        nextProducer, mCloseableImageCopier, mExecutorSupplier.forBackground());
  }

  public static RemoveImageTransformMetaDataProducer newRemoveImageTransformMetaDataProducer(
      Producer<Pair<CloseableReference<PooledByteBuffer>, ImageTransformMetaData>> nextProducer) {
    return new RemoveImageTransformMetaDataProducer(nextProducer);
  }

  public ResizeAndRotateProducer newResizeAndRotateProducer(
      Producer<Pair<CloseableReference<PooledByteBuffer>, ImageTransformMetaData>> nextProducer) {
    return new ResizeAndRotateProducer(
        mExecutorSupplier.forTransform(),
        mPooledByteBufferFactory,
        nextProducer);
  }

  public static <T> SwallowResultProducer<T> newSwallowResultProducer(Producer<T> nextProducer) {
    return new SwallowResultProducer<T>(nextProducer);
  }

  public <T> ThreadHandoffProducer<T> newBackgroundThreadHandoffProducer(Producer<T> nextProducer) {
    return new ThreadHandoffProducer<T>(mExecutorSupplier.forBackground(), nextProducer);
  }

  public static <T> ThrottlingProducer<T> newThrottlingProducer(
      int maxSimultaneousRequests,
      Producer<T> nextProducer) {
    return new ThrottlingProducer<T>(maxSimultaneousRequests, nextProducer);
  }

  public WebpTranscodeProducer newWebpTranscodeProducer(
      Producer<CloseableReference<PooledByteBuffer>> nextProducer) {
    return new WebpTranscodeProducer(
        mExecutorSupplier.forTransform(),
        mPooledByteBufferFactory,
        nextProducer);
  }
}
