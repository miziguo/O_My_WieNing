package o.my.wiening;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class MonitorGroupAdapter extends RecyclerView.Adapter<MonitorGroupAdapter.GroupViewHolder> {

    private List<MonitorGroup> groupList;
    private OnDeleteClickListener onDeleteClickListener;

    public interface OnDeleteClickListener {
        void onDeleteClick(int position);
    }

    public MonitorGroupAdapter(List<MonitorGroup> groupList, OnDeleteClickListener listener) {
        this.groupList = groupList;
        this.onDeleteClickListener = listener;
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
        holder.deleteButton.setOnClickListener(v -> onDeleteClickListener.onDeleteClick(holder.getAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return groupList.size();
    }

    static class GroupViewHolder extends RecyclerView.ViewHolder {
        TextView sourcePath;
        TextView targetPath;
        ImageButton deleteButton;

        GroupViewHolder(View itemView) {
            super(itemView);
            sourcePath = itemView.findViewById(R.id.tv_item_source_path);
            targetPath = itemView.findViewById(R.id.tv_item_target_path);
            deleteButton = itemView.findViewById(R.id.btn_item_delete);
        }
    }
}
