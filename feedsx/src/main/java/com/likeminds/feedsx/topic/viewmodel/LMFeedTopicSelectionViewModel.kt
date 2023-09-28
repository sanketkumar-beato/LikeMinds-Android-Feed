package com.likeminds.feedsx.topic.viewmodel

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.likeminds.feedsx.topic.model.LMFeedAllTopicsViewData
import com.likeminds.feedsx.utils.ViewDataConverter
import com.likeminds.feedsx.utils.coroutine.launchIO
import com.likeminds.feedsx.utils.model.BaseViewType
import com.likeminds.likemindsfeed.LMFeedClient
import com.likeminds.likemindsfeed.topic.model.GetTopicRequest
import javax.inject.Inject

class LMFeedTopicSelectionViewModel @Inject constructor() : ViewModel() {
    companion object {
        const val SEARCH_TYPE = "name"
        const val PAGE_SIZE = 20
    }

    private val lmFeedClient = LMFeedClient.getInstance()

    //first -> page
    //second -> list of topics
    private val _topicsViewData = MutableLiveData<Pair<Int, List<BaseViewType>>>()
    val topicsViewData: LiveData<Pair<Int, List<BaseViewType>>> = _topicsViewData

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    fun getTopics(showAllTopicFilter: Boolean, page: Int, searchString: String? = null) {
        viewModelScope.launchIO {

            val requestBuilder = GetTopicRequest.Builder()
                .page(page)
                .pageSize(PAGE_SIZE)

            if (!searchString.isNullOrEmpty()) {
                requestBuilder.search(searchString)
                    .searchType(SEARCH_TYPE)
            }

            val request = requestBuilder.build()

            val response = lmFeedClient.getTopics(request)

            if (response.success) {
                val topics = response.data?.topics ?: emptyList()
                val topicsViewData = topics.map { topic ->
                    ViewDataConverter.convertTopic(topic)
                }

                val viewTypes = ArrayList<BaseViewType>()
                if (page == 1 && showAllTopicFilter) {
                    val allTopicsFilter = LMFeedAllTopicsViewData.Builder().build()
                    viewTypes.add(allTopicsFilter)
                }

                viewTypes.addAll(topicsViewData)

                _topicsViewData.postValue(Pair(page, viewTypes.toList()))
            } else {
                val errorMessage = response.errorMessage
                _errorMessage.postValue(errorMessage)
            }
        }
    }
}