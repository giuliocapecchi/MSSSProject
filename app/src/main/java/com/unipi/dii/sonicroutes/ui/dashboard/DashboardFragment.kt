package com.unipi.dii.sonicroutes.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.unipi.dii.sonicroutes.R
import java.io.File

class DashboardFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FileAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val rootView = inflater.inflate(R.layout.fragment_dashboard, container, false)
        recyclerView = rootView.findViewById(R.id.recycler_view_dashboard)
        adapter = FileAdapter(getDataFiles().toMutableList())
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        return rootView
    }

    private fun getDataFiles(): List<File> {
        val context = requireContext().applicationContext
        return context.filesDir.listFiles { file ->
            file.name.startsWith("data_")
        }?.toList() ?: emptyList()
    }
}
