package o.my.wiening;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class MonitorGroupAdapter extends RecyclerView.Adapter<MonitorGroupAdapter.GroupViewHolder> {

    private List<MonitorGroup> groupList;
    private OnDeleteClickListener onDeleteClickListener;
    private int cardAlpha = 255; // ★ 新增：默认 alpha 值为不透明 (255)

    public interface OnDeleteClickListener {
        void onDeleteClick(int position);
    }

    public MonitorGroupAdapter(List<MonitorGroup> groupList, OnDeleteClickListener listener) {
        this.groupList = groupList;
        this.onDeleteClickListener = listener;
    }

    // ★ 新增：一个用于从外部更新 alpha 值的方法
    public void setCardAlpha(int alpha) {
        if (this.cardAlpha != alpha) {
            this.cardAlpha = alpha;
            notifyDataSetChanged(); // 通知所有项重绘以应用新透明度
        }
    }

    @NonNull
    @Override
    public GroupViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_monitor_group, parent, false);
        return new GroupViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull GroupViewHolder holder, int position) {
        MonitorGroup group = groupList.get(position);
        holder.sourcePath.setText("源: " + group.getSourcePath());
        holder.targetPath.setText("目标: " + group.getTargetPath());
        holder.deleteButton.setOnClickListener(v -> {
            if (onDeleteClickListener != null) {
                onDeleteClickListener.onDeleteClick(holder.getAdapterPosition());
            }
        });

        // ★ 新增：在这里为卡片设置带有透明度的背景色
        // 使用一个浅灰色 #f0f0f0 作为底色
        int color = Color.argb(cardAlpha, 255, 255, 255);
        holder.cardView.setCardBackgroundColor(color);
        // ★★★ 修复点 2：同样应用阴影逻辑 ★★★
        float elevation = (cardAlpha == 255) ? holder.itemView.getContext().getResources().getDisplayMetrics().density * 4 : 0f;

        holder.cardView.setCardBackgroundColor(color);
        holder.cardView.setCardElevation(elevation);

    }

    @Override
    public int getItemCount() {
        return groupList.size();
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        CardView cardView; // ★ 新增：获取整个 CardView 的引用
        TextView sourcePath;
        TextView targetPath;
        ImageButton deleteButton;

        GroupViewHolder(View itemView) {
            super(itemView);
            // item_monitor_group.xml 的根布局就是 CardView
            cardView = (CardView) itemView;
            sourcePath = itemView.findViewById(R.id.tv_item_source_path);
            targetPath = itemView.findViewById(R.id.tv_item_target_path);
            deleteButton = itemView.findViewById(R.id.btn_item_delete);
        }
    }
}
