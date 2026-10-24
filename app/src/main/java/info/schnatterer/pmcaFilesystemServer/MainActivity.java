package info.schnatterer.pmcaFilesystemServer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.io.PrintWriter;
import java.io.StringWriter;

public class MainActivity extends BaseActivity implements AdapterView.OnItemClickListener {
    protected class ActivityListItem extends ListAdapter.ListItem {
        private int nameResource;
        private Class<? extends Activity> clazz;

        public ActivityListItem(int nameResource, Class<? extends Activity> clazz) {
            this.nameResource = nameResource;
            this.clazz = clazz;
        }

        @Override
        public String getText1() {
            return getResources().getString(nameResource);
        }

        public Class<? extends Activity> getActivityClass() {
            return clazz;
        }
    }

    private ListView listView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.list);

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                StringWriter sw = new StringWriter();
                sw.append(throwable.toString());
                sw.append("\n");
                throwable.printStackTrace(new PrintWriter(sw));
                Logger.error(sw.toString());

                System.exit(0);
            }
        });

        listView = (ListView) findViewById(R.id.listView);
        listView.setOnItemClickListener(this);
        showMenu();
    }

    private void showMenu() {
        final boolean exposeFullFilesystem = ServerSettings.isExposeFullFilesystem(this);
        ListAdapter.ListItem[] items = new ListAdapter.ListItem[]{
                new ActivityListItem(R.string.title_activity_wifi, WifiActivity.class),
                new ActivityListItem(R.string.title_activity_wifi_direct, WifiDirectActivity.class),
                new ActivityListItem(R.string.title_activity_wifi_setting, WifiSettingActivity.class),
                new ListAdapter.ListItem() {
                    @Override
                    public String getText1() {
                        return getResources().getString(R.string.title_expose_filesystem);
                    }

                    @Override
                    public String getText2() {
                        return getResources().getString(exposeFullFilesystem
                                ? R.string.expose_filesystem_on
                                : R.string.expose_filesystem_off);
                    }
                }
        };
        listView.setAdapter(new ListAdapter<ListAdapter.ListItem>(this, items));
    }

    @Override
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
        ListAdapter.ListItem item = (ListAdapter.ListItem) adapterView.getItemAtPosition(position);
        if (item instanceof ActivityListItem) {
            startActivity(new Intent(this, ((ActivityListItem) item).getActivityClass()));
        } else {
            ServerSettings.setExposeFullFilesystem(this, !ServerSettings.isExposeFullFilesystem(this));
            showMenu();
        }
    }
}
