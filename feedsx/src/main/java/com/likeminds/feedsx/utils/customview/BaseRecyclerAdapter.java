package com.likeminds.feedsx.utils.customview;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.recyclerview.widget.RecyclerView;

import com.likeminds.feedsx.utils.model.BaseViewType;
import com.likeminds.feedsx.utils.model.ViewType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public abstract class BaseRecyclerAdapter<T extends BaseViewType> extends RecyclerView.Adapter<DataBoundViewHolder> {

    private static final String TAG = "BaseRecyclerAdapter";
    private List<T> dataList;
    private final SparseArray<ViewDataBinder> supportedViewBinderResolverMap;

    public BaseRecyclerAdapter() {
        dataList = new ArrayList<>(1);
        supportedViewBinderResolverMap = new SparseArray<>(1);
    }

    protected abstract List<ViewDataBinder> getSupportedViewDataBinder();

    public void initViewDataBinders() {
        for (ViewDataBinder viewDataBinder : getSupportedViewDataBinder()) {
            supportedViewBinderResolverMap.put(viewDataBinder.getViewType(), viewDataBinder);
        }
    }

    @Override
    public DataBoundViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        ViewDataBinder viewDataBinder = supportedViewBinderResolverMap.get(viewType);

        if (viewDataBinder == null) {
            String message = String.format(Locale.US, "No view binder found for viewType: %d", viewType);
            throw new IllegalArgumentException(message);
        }

        return new DataBoundViewHolder(viewDataBinder.createBinder(parent));
    }

    @Override
    public final void onBindViewHolder(DataBoundViewHolder holder, int position) {
        ViewDataBinder viewDataBinder = supportedViewBinderResolverMap.get(getItemViewType(position));

        if (viewDataBinder == null) {
            Log.e(TAG, "Please add the supported view binder to view binders list in adapter");
            return;
        }
        viewDataBinder.bindData(holder.getBinding(), dataList.get(position), position);
        holder.getBinding().executePendingBindings();
    }

    @Override
    public void onBindViewHolder(@NonNull DataBoundViewHolder holder, int position, List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            ViewDataBinder viewDataBinder = supportedViewBinderResolverMap.get(getItemViewType(position));

            if (viewDataBinder == null) {
                Log.e(TAG, "Please add the supported view binder to view binders list in adapter");
                return;
            }
            viewDataBinder.bindData();
        }
    }

    @Override
    public int getItemViewType(int position) {
        if (position >= 0 && position < dataList.size()) {
            T item = dataList.get(position);
            if (item != null) {
                return item.getViewType();
            }
        }
        return 0;
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    public List<T> items() {
        return dataList;
    }

    public void setItems(List<T> items) {
        this.dataList = items;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void notifyAllItems() {
        notifyDataSetChanged();
    }

    public void add(int position, T baseViewType) {
        dataList.add(position, baseViewType);
        notifyItemInserted(position);
    }

    public void add(T baseViewType) {
        dataList.add(baseViewType);
        notifyItemChanged(dataList.size() - 1);
    }

    public void addAll(List<T> baseViewTypes) {
        dataList.addAll(baseViewTypes);
        notifyItemRangeInserted(dataList.size(), baseViewTypes.size());
    }

    public void update(int position, T baseViewType) {
        dataList.set(position, baseViewType);
        notifyItemChanged(position);
    }

    // updates an item in rv without notifying
    public void updateWithoutNotifyingRV(int position, T baseViewType) {
        dataList.set(position, baseViewType);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void replace(List<T> dataList) {
        this.dataList = dataList;
        notifyDataSetChanged();
    }

    public void removeIndex(int index) {
        dataList.remove(index);
        notifyItemRemoved(index);
    }

    public void removeRange(int startIndex) {
        int size = dataList.size();
        dataList.subList(startIndex, size).clear();
        notifyItemRangeRemoved(startIndex, size - startIndex);
    }

    public void removeRange(int startIndex, int endIndex) {
        int count;
        if (endIndex >= dataList.size()) {
            dataList.subList(startIndex, dataList.size()).clear();
            count = dataList.size() - startIndex;
        } else {
            dataList.subList(startIndex, endIndex + 1).clear();
            count = endIndex - startIndex + 1;
        }
        notifyItemRangeRemoved(startIndex, count);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void removeIndexWithNotifyDataSetChanged(int index) {
        dataList.remove(index);
        notifyDataSetChanged();
    }

    public void removeLast() {
        int lastIndex = dataList.size() - 1;
        removeIndex(lastIndex);
    }

    @SuppressLint("NotifyDataSetChanged")
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void removeViewType(@ViewType int viewType) {
        dataList.removeIf(item -> item.getViewType() == viewType);
        notifyDataSetChanged();
    }

    public void clear() {
        dataList.clear();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void clearAndNotify() {
        dataList.clear();
        notifyDataSetChanged();
    }

    public void addAll(int position, List<T> baseViewTypes) {
        dataList.addAll(position, baseViewTypes);
        notifyItemRangeInserted(position, baseViewTypes.size());
    }

    public void addWithNotifyItemInserted(T baseViewType) {
        dataList.add(baseViewType);
        notifyItemInserted(dataList.size() - 1);
    }

    public void addAllWithoutNotify(List<T> baseViewTypes) {
        dataList.addAll(baseViewTypes);
    }

    public void addWithNotifyItemRangeChanged(int position, T baseViewType) {
        dataList.add(position, baseViewType);
        notifyItemRangeChanged(position, dataList.size());
    }
}
