package com.likeminds.feedsx.feed.view

import android.app.Activity
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.material.snackbar.Snackbar
import com.likeminds.feedsx.FeedSXApplication.Companion.LOG_TAG
import com.likeminds.feedsx.R
import com.likeminds.feedsx.branding.model.BrandingData
import com.likeminds.feedsx.databinding.FragmentFeedBinding
import com.likeminds.feedsx.delete.model.DELETE_TYPE_POST
import com.likeminds.feedsx.delete.model.DeleteExtras
import com.likeminds.feedsx.delete.view.AdminDeleteDialogFragment
import com.likeminds.feedsx.delete.view.SelfDeleteDialogFragment
import com.likeminds.feedsx.feed.viewmodel.FeedViewModel
import com.likeminds.feedsx.likes.model.LikesScreenExtras
import com.likeminds.feedsx.likes.model.POST
import com.likeminds.feedsx.likes.view.LikesActivity
import com.likeminds.feedsx.media.model.MEDIA_ACTION_NONE
import com.likeminds.feedsx.media.model.MEDIA_ACTION_PAUSE
import com.likeminds.feedsx.media.model.MEDIA_ACTION_PLAY
import com.likeminds.feedsx.media.util.LMExoplayer
import com.likeminds.feedsx.media.util.LMExoplayerListener
import com.likeminds.feedsx.notificationfeed.view.NotificationFeedActivity
import com.likeminds.feedsx.overflowmenu.model.DELETE_POST_MENU_ITEM
import com.likeminds.feedsx.overflowmenu.model.PIN_POST_MENU_ITEM
import com.likeminds.feedsx.overflowmenu.model.REPORT_POST_MENU_ITEM
import com.likeminds.feedsx.overflowmenu.model.UNPIN_POST_MENU_ITEM
import com.likeminds.feedsx.post.create.view.CreatePostActivity
import com.likeminds.feedsx.post.detail.model.PostDetailExtras
import com.likeminds.feedsx.post.detail.view.PostDetailActivity
import com.likeminds.feedsx.posttypes.model.PostViewData
import com.likeminds.feedsx.posttypes.model.UserViewData
import com.likeminds.feedsx.posttypes.view.adapter.PostAdapter
import com.likeminds.feedsx.posttypes.view.adapter.PostAdapterListener
import com.likeminds.feedsx.report.model.REPORT_TYPE_POST
import com.likeminds.feedsx.report.model.ReportExtras
import com.likeminds.feedsx.report.view.ReportActivity
import com.likeminds.feedsx.report.view.ReportFragment
import com.likeminds.feedsx.report.view.ReportSuccessDialog
import com.likeminds.feedsx.utils.*
import com.likeminds.feedsx.utils.ViewUtils.hide
import com.likeminds.feedsx.utils.ViewUtils.show
import com.likeminds.feedsx.utils.ViewUtils.showShortToast
import com.likeminds.feedsx.utils.customview.BaseFragment
import com.likeminds.feedsx.utils.mediauploader.MediaUploadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.onEach
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class FeedFragment :
    BaseFragment<FragmentFeedBinding>(),
    PostAdapterListener,
    AdminDeleteDialogFragment.DeleteDialogListener,
    SelfDeleteDialogFragment.DeleteAlertDialogListener,
    LMExoplayerListener {

    private val viewModel: FeedViewModel by viewModels()

    private lateinit var mSwipeRefreshLayout: SwipeRefreshLayout
    private lateinit var mPostAdapter: PostAdapter
    private lateinit var mScrollListener: EndlessRecyclerScrollListener

    @Inject
    lateinit var lmExoplayer: LMExoplayer

    private var alreadyPosting: Boolean = false
    private val workersMap by lazy { ArrayList<UUID>() }

    override fun getViewBinding(): FragmentFeedBinding {
        return FragmentFeedBinding.inflate(layoutInflater)
    }

    override fun setUpViews() {
        super.setUpViews()
        initUI()
        initiateSDK()
        initToolbar()
    }

    override fun observeData() {
        super.observeData()
        observePosting()

        // observes userResponse LiveData
        viewModel.userResponse.observe(viewLifecycleOwner) { response ->
            observeUserResponse(response)
        }

        // observes logoutResponse LiveData
        viewModel.logoutResponse.observe(viewLifecycleOwner) {
            Log.d(
                LOG_TAG,
                "initiate api sdk called -> success and have not app access"
            )
            showInvalidAccess()
        }

        // observe universal feed
        viewModel.universalFeedResponse.observe(viewLifecycleOwner) { pair ->
            observeFeedUniversal(pair)
        }

        viewModel.deletePostResponse.observe(viewLifecycleOwner) { postId ->
            val indexToRemove = getIndexAndPostFromAdapter(postId).first
            mPostAdapter.removeIndex(indexToRemove)
            ViewUtils.showShortToast(
                requireContext(),
                getString(R.string.post_deleted)
            )
        }

        viewModel.pinPostResponse.observe(viewLifecycleOwner) { postId ->
            val post = getIndexAndPostFromAdapter(postId).second
            if (post.isPinned) {
                Snackbar.make(binding.root, "Post pinned to top!", Snackbar.LENGTH_LONG)
                    .setAction("Refresh") {
                        refreshFeed()
                    }
                    .show()
            } else {
                ViewUtils.showShortToast(requireContext(), "Post unpinned!")
            }
        }

        //observes errorMessage for the apis
        viewModel.errorMessageEventFlow.onEach { response ->
            observeErrorMessage(response)
        }.observeInLifecycle(viewLifecycleOwner)
    }

    // observes user response from InitiateUser
    private fun observeUserResponse(user: UserViewData?) {
        initToolbar()
        setUserImage(user)
    }

    //observe error handling
    private fun observeErrorMessage(response: FeedViewModel.ErrorMessageEvent) {
        when (response) {
            is FeedViewModel.ErrorMessageEvent.InitiateUser -> {
                val errorMessage = response.errorMessage
                ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
            }
            is FeedViewModel.ErrorMessageEvent.UniversalFeed -> {
                val errorMessage = response.errorMessage
                mSwipeRefreshLayout.isRefreshing = false
                ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
            }
            is FeedViewModel.ErrorMessageEvent.LikePost -> {
                val postId = response.postId

                //get post and index
                val pair = getIndexAndPostFromAdapter(postId)
                val post = pair.second
                val index = pair.first

                //update post view data
                val updatedPost = post.toBuilder()
                    .isLiked(false)
                    .fromPostLiked(true)
                    .likesCount(post.likesCount - 1)
                    .build()

                //update recycler view
                mPostAdapter.update(index, updatedPost)

                //show error message
                val errorMessage = response.errorMessage
                ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
            }
            is FeedViewModel.ErrorMessageEvent.SavePost -> {
                val postId = response.postId

                //get post and index
                val pair = getIndexAndPostFromAdapter(postId)
                val post = pair.second
                val index = pair.first

                //update post view data
                val updatedPost = post.toBuilder()
                    .isSaved(false)
                    .fromPostSaved(true)
                    .build()

                //update recycler view
                mPostAdapter.update(index, updatedPost)

                //show error message
                val errorMessage = response.errorMessage
                ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
            }
            is FeedViewModel.ErrorMessageEvent.DeletePost -> {
                val errorMessage = response.errorMessage
                ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
            }
            is FeedViewModel.ErrorMessageEvent.PinPost -> {
                val postId = response.postId

                //get post and index
                val pair = getIndexAndPostFromAdapter(postId)
                val post = pair.second
                val index = pair.first

                //update post view data
                val updatedPost = post.toBuilder()
                    .isPinned(!post.isPinned)
                    .build()

                //update recycler view
                mPostAdapter.update(index, updatedPost)

                //show error message
                val errorMessage = response.errorMessage
                ViewUtils.showErrorMessageToast(requireContext(), errorMessage)
            }
            is FeedViewModel.ErrorMessageEvent.AddPost -> {
                ViewUtils.showErrorMessageToast(requireContext(), response.errorMessage)
                removePostingView()
            }
        }
    }

    private fun observePosting() {
        viewModel.postDataEventFlow.onEach { response ->
            when (response) {
                // when the post data comes from local db
                is FeedViewModel.PostDataEvent.PostDbData -> {
                    alreadyPosting = true
                    val post = response.post
                    binding.layoutPosting.apply {
                        root.show()
                        if (post.thumbnail.isNullOrEmpty()) {
                            ivPostThumbnail.hide()
                        } else {
                            ivPostThumbnail.show()
                            ivPostThumbnail.setImageURI(Uri.parse(post.thumbnail))
                        }
                        postingProgress.progress = 0
                        postingProgress.show()
                        ivPosted.hide()
                        tvRetry.hide()
                        observeMediaUpload(post)
                    }
                }
                // when the post data comes from api response
                is FeedViewModel.PostDataEvent.PostResponseData -> {
                    binding.apply {
                        mPostAdapter.add(0, response.post)
                        recyclerView.scrollToPosition(0)
                        removePostingView()
                    }
                }
            }
        }.observeInLifecycle(viewLifecycleOwner)
    }


    //observe feed response
    private fun observeFeedUniversal(pair: Pair<Int, List<PostViewData>>) {
        //hide progress bar
        ProgressHelper.hideProgress(binding.progressBar)
        //page in api send
        val page = pair.first

        //list of post
        val feed = pair.second

        //if pull to refresh is called
        if (mSwipeRefreshLayout.isRefreshing) {
            setFeedAndScrollToTop(feed)
            mSwipeRefreshLayout.isRefreshing = false
        }

        //normal adding
        if (page == 1) {
            setFeedAndScrollToTop(feed)
        } else {
            mPostAdapter.addAll(feed)
        }
    }

    // removes the posting view and shows create post button
    private fun removePostingView() {
        binding.apply {
            alreadyPosting = false
            layoutPosting.root.hide()
        }
    }

    // finds the upload worker by UUID and observes the worker
    private fun observeMediaUpload(postingData: PostViewData) {
        if (postingData.uuid.isEmpty()) {
            return
        }
        val uuid = UUID.fromString(postingData.uuid)
        if (!workersMap.contains(uuid)) {
            workersMap.add(uuid)
            WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(uuid)
                .observe(viewLifecycleOwner) { workInfo ->
                    observeMediaWorker(workInfo, postingData)
                }
        }
    }

    // observes the media worker through various worker lifecycle
    private fun observeMediaWorker(
        workInfo: WorkInfo,
        postingData: PostViewData
    ) {
        when (workInfo.state) {
            WorkInfo.State.SUCCEEDED -> {
                // uploading completed, call the add post api
                binding.layoutPosting.apply {
                    postingProgress.hide()
                    tvRetry.hide()
                    ivPosted.show()
                }
                viewModel.addPost(postingData)
            }
            WorkInfo.State.FAILED -> {
                // uploading failed, initiate retry mechanism
                val indexList = workInfo.outputData.getIntArray(
                    MediaUploadWorker.ARG_MEDIA_INDEX_LIST
                ) ?: return
                initRetryAction(
                    postingData.temporaryId,
                    indexList.size
                )
            }
            else -> {
                // uploading in progress, map the progress to progress bar
                val progress = MediaUploadWorker.getProgress(workInfo) ?: return
                binding.layoutPosting.apply {
                    val percentage = (((1.0 * progress.first) / progress.second) * 100)
                    val progressValue = percentage.toInt()
                    postingProgress.progress = progressValue
                }
            }
        }
    }

    // initializes retry mechanism for attachments uploading
    private fun initRetryAction(temporaryId: Long?, attachmentCount: Int) {
        binding.layoutPosting.apply {
            ivPosted.hide()
            postingProgress.hide()
            tvRetry.show()
            tvRetry.setOnClickListener {
                viewModel.createRetryPostMediaWorker(
                    requireContext(),
                    temporaryId,
                    attachmentCount
                )
            }
        }
    }

    // shows invalid access error and logs out invalid user
    private fun showInvalidAccess() {
        binding.apply {
            recyclerView.hide()
            layoutAccessRemoved.root.show()
            memberImage.hide()
            ivSearch.hide()
            ivNotification.hide()
        }
    }

    override fun onStart() {
        super.onStart()
        initializeExoplayer()
    }

    override fun onStop() {
        super.onStop()
        //release player
        lmExoplayer.release()
    }

    // initiates SDK
    private fun initiateSDK() {
        ProgressHelper.showProgress(binding.progressBar)
        viewModel.initiateUser(
            "69edd43f-4a5e-4077-9c50-2b7aa740acce",
            "029f66a8-264b-413f-a9df-3ae2f4166486",
            "Ishaan",
            false
        )
    }

    /**
     * UI Block
     **/

    // initializes various UI components
    private fun initUI() {
        //TODO: Set as per branding
        binding.isBrandingBasic = true

        initRecyclerView()
        initSwipeRefreshLayout()
        handleNewPostClick()
    }

    // handles new post fab click
    private fun handleNewPostClick() {
        binding.newPostButton.setOnClickListener {
            if (alreadyPosting) {
                showShortToast(requireContext(), getString(R.string.a_post_is_already_uploading))
            } else {
                val intent = CreatePostActivity.getIntent(requireContext())
                createPostLauncher.launch(intent)
            }
        }
    }

    // launcher for [CreatePostActivity]
    private val createPostLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    // post of type text/link has been created and posted
                    refreshFeed()
                }
                CreatePostActivity.RESULT_UPLOAD_POST -> {
                    // post with attachments created, now upload and post it from db
                    viewModel.fetchPendingPostFromDB()
                }
            }
        }

    // initializes universal feed recyclerview
    private fun initRecyclerView() {
        val linearLayoutManager = LinearLayoutManager(context)
        mPostAdapter = PostAdapter(this)
        binding.recyclerView.apply {
            layoutManager = linearLayoutManager
            adapter = mPostAdapter
            if (itemAnimator is SimpleItemAnimator)
                (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false
            show()
        }
        attachScrollListener(
            binding.recyclerView,
            linearLayoutManager
        )
    }

    // initializes swipe refresh layout and sets refresh listener
    private fun initSwipeRefreshLayout() {
        mSwipeRefreshLayout = binding.swipeRefreshLayout
        mSwipeRefreshLayout.setColorSchemeColors(
            BrandingData.getButtonsColor(),
        )

        mSwipeRefreshLayout.setOnRefreshListener {
            refreshFeed()
        }
    }

    //set posts through diff utils and scroll to top of the feed
    private fun setFeedAndScrollToTop(feed: List<PostViewData>) {
        mPostAdapter.replace(feed)
        binding.recyclerView.scrollToPosition(0)
    }

    //refresh the whole feed
    private fun refreshFeed() {
        mSwipeRefreshLayout.isRefreshing = true
        mScrollListener.resetData()
        viewModel.getUniversalFeed(1)
    }

    //attach scroll listener for pagination
    private fun attachScrollListener(
        recyclerView: RecyclerView,
        layoutManager: LinearLayoutManager
    ) {
        mScrollListener = object : EndlessRecyclerScrollListener(layoutManager) {
            override fun onLoadMore(currentPage: Int) {
                if (currentPage > 0) {
                    viewModel.getUniversalFeed(currentPage)
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                val isExtended = binding.newPostButton.isExtended

                // Scroll down
                if (dy > 20 && isExtended) {
                    binding.newPostButton.shrink()
                }

                // Scroll up
                if (dy < -20 && !isExtended) {
                    binding.newPostButton.extend()
                }

                // At the top
                if (!recyclerView.canScrollVertically(-1)) {
                    binding.newPostButton.extend()
                }
            }
        }

        recyclerView.addOnScrollListener(mScrollListener)
    }

    private fun initToolbar() {
        (requireActivity() as AppCompatActivity).setSupportActionBar(binding.toolbar)

        //if user is guest user hide, profile icon from toolbar
        binding.memberImage.isVisible = !isGuestUser

        //click listener -> open profile screen
        binding.memberImage.setOnClickListener {
            //TODO: On member Image click
        }

        binding.ivNotification.setOnClickListener {
            NotificationFeedActivity.start(requireContext())
        }

        binding.ivSearch.setOnClickListener {
            //TODO: perform search
        }

        //TODO: testing data. add this while observing data
        binding.tvNotificationCount.text = "10"
    }

    // sets user profile image
    private fun setUserImage(user: UserViewData?) {
        if (user != null) {
            MemberImageUtil.setImage(
                user.imageUrl,
                user.name,
                user.userUniqueId,
                binding.memberImage,
                showRoundImage = true,
                objectKey = user.updatedAt
            )
        }
    }

    /**
     * Post Actions block
     **/
    override fun updateSeenFullContent(position: Int, alreadySeenFullContent: Boolean) {
        val item = mPostAdapter[position]
        if (item is PostViewData) {
            val newViewData = item.toBuilder()
                .alreadySeenFullContent(alreadySeenFullContent)
                .fromPostSaved(false)
                .fromPostLiked(false)
                .fromVideoAction(false)
                .build()
            mPostAdapter.update(position, newViewData)
        }
    }

    override fun savePost(position: Int) {
        //get item
        val item = mPostAdapter[position]
        if (item is PostViewData) {
            //update the post view data
            val newViewData = item.toBuilder()
                .fromPostSaved(true)
                .isSaved(!item.isSaved)
                .build()
            //call api
            viewModel.savePost(newViewData.id)

            //update recycler
            mPostAdapter.update(position, newViewData)
        }
    }

    override fun likePost(position: Int) {
        //get item
        val item = mPostAdapter[position]
        if (item is PostViewData) {
            //new like count
            val newLikesCount = if (item.isLiked) {
                item.likesCount - 1
            } else {
                item.likesCount + 1
            }

            //update post view data
            val newViewData = item.toBuilder()
                .fromPostLiked(true)
                .isLiked(!item.isLiked)
                .likesCount(newLikesCount)
                .build()

            //call api
            viewModel.likePost(newViewData.id)
            //update recycler
            mPostAdapter.update(position, newViewData)
        }
    }

    override fun onPostMenuItemClicked(postId: String, title: String) {
        Log.d("PUI", "postId menu: $postId")
        when (title) {
            DELETE_POST_MENU_ITEM -> {
                deletePost(postId)
            }
            REPORT_POST_MENU_ITEM -> {
                reportPost(postId)
            }
            PIN_POST_MENU_ITEM -> {
                pinPost(postId)
            }
            UNPIN_POST_MENU_ITEM -> {
                unpinPost(postId)
            }
        }
    }

    // opens likes screen when likes count is clicked.
    override fun showLikesScreen(postId: String) {
        val likesScreenExtras = LikesScreenExtras.Builder()
            .postId(postId)
            .entityType(POST)
            .build()
        LikesActivity.start(requireContext(), likesScreenExtras)
    }

    //opens post detail screen when add comment/comments count is clicked
    override fun comment(postId: String) {
        val postDetailExtras = PostDetailExtras.Builder()
            .postId(postId)
            .isEditTextFocused(true)
            .build()
        PostDetailActivity.start(requireContext(), postDetailExtras)
    }

    //opens post detail screen when post content is clicked
    override fun postDetail(postData: PostViewData) {
        val postDetailExtras = PostDetailExtras.Builder()
            .postId(postData.id)
            .isEditTextFocused(false)
            .build()
        PostDetailActivity.start(requireContext(), postDetailExtras)
    }

    // callback when self post is deleted by user
    override fun selfDelete(deleteExtras: DeleteExtras) {
        viewModel.deletePost(deleteExtras.postId)
    }

    // callback when other's post is deleted by CM
    override fun adminDelete(deleteExtras: DeleteExtras, reason: String) {
        viewModel.deletePost(deleteExtras.postId, reason)
    }

    // updates the fromPostLiked/fromPostSaved variables and updates the rv list
    override fun updateFromLikedSaved(position: Int) {
        var postData = mPostAdapter[position] as PostViewData
        postData = postData.toBuilder()
            .fromPostLiked(false)
            .fromPostSaved(false)
            .fromVideoAction(false)
            .build()
        mPostAdapter.updateWithoutNotifyingRV(position, postData)
    }

    // processes delete post request
    private fun deletePost(postId: String) {
        val post = getIndexAndPostFromAdapter(postId).second
        val deleteExtras = DeleteExtras.Builder()
            .postId(postId)
            .entityType(DELETE_TYPE_POST)
            .build()

        if (post.userId == viewModel.getUserUniqueId()) {
            SelfDeleteDialogFragment.showDialog(
                childFragmentManager,
                deleteExtras
            )
        } else {
            AdminDeleteDialogFragment.showDialog(
                childFragmentManager,
                deleteExtras
            )
        }
    }

    // Processes report action on post
    private fun reportPost(postId: String) {
        val post = getIndexAndPostFromAdapter(postId).second

        //create extras for [ReportActivity]
        val reportExtras = ReportExtras.Builder()
            .entityId(postId)
            .entityCreatorId(post.userId)
            .entityType(REPORT_TYPE_POST)
            .build()

        //get Intent for [ReportActivity]
        val intent = ReportActivity.getIntent(requireContext(), reportExtras)

        //start [ReportActivity] and check for result
        reportPostLauncher.launch(intent)
    }

    // launcher to start [ReportActivity] and show success dialog for result
    private val reportPostLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data?.getStringExtra(ReportFragment.REPORT_RESULT)
                ReportSuccessDialog(data ?: "").show(
                    childFragmentManager,
                    ReportSuccessDialog.TAG
                )
            }
        }

    private fun pinPost(postId: String) {
        //get item
        val postAndIndex = getIndexAndPostFromAdapter(postId)
        val index = postAndIndex.first
        val post = postAndIndex.second

        //get pin menu item
        val menuItems = post.menuItems.toMutableList()
        val pinPostIndex = menuItems.indexOfFirst {
            (it.title == PIN_POST_MENU_ITEM)
        }

        //if pin item doesn't exist
        if (pinPostIndex == -1) return

        //update pin menu item
        val pinPostMenuItem = menuItems[pinPostIndex]
        val newPinPostMenuItem = pinPostMenuItem.toBuilder().title(UNPIN_POST_MENU_ITEM).build()
        menuItems[pinPostIndex] = newPinPostMenuItem

        //update the post view data
        val newViewData = post.toBuilder()
            .isPinned(!post.isPinned)
            .menuItems(menuItems)
            .build()

        //call api
        viewModel.pinPost(postId)

        //update recycler
        mPostAdapter.update(index, newViewData)
    }

    private fun unpinPost(postId: String) {
        //get item
        val postAndIndex = getIndexAndPostFromAdapter(postId)
        val index = postAndIndex.first
        val post = postAndIndex.second

        //get unpin menu item
        val menuItems = post.menuItems.toMutableList()
        val unPinPostIndex = menuItems.indexOfFirst {
            (it.title == UNPIN_POST_MENU_ITEM)
        }

        //if unpin item doesn't exist
        if (unPinPostIndex == -1) return

        //update unpin menu item
        val unPinPostMenuItem = menuItems[unPinPostIndex]
        val newUnPinPostMenuItem = unPinPostMenuItem.toBuilder().title(PIN_POST_MENU_ITEM).build()
        menuItems[unPinPostIndex] = newUnPinPostMenuItem

        //update the post view data
        val newViewData = post.toBuilder()
            .isPinned(!post.isPinned)
            .menuItems(menuItems)
            .build()

        //call api
        viewModel.pinPost(postId)

        //update recycler
        mPostAdapter.update(index, newViewData)
    }

    /**
     * Media Block
     **/

    //initialize exo player
    private fun initializeExoplayer() {
        lmExoplayer.initialize(this)
    }

    override fun videoEnded(positionOfItemInAdapter: Int) {
        super.videoEnded(positionOfItemInAdapter)
//        if (positionOfItemInAdapter == -1) return
//
//        val post = getPostFromAdapter(positionOfItemInAdapter)
//        val attachment = post.attachments.first()
//        val newAttachments = attachment.toBuilder()
//            .mediaActions(MEDIA_ACTION_NONE)
//            .build()
//        val newPost = post.toBuilder()
//            .attachments(listOf(newAttachments))
//            .fromVideoAction(true)
//            .build()
//        mPostAdapter.update(positionOfItemInAdapter, newPost)
    }

    override fun sendMediaItemToExoPlayer(
        position: Int,
        playerView: StyledPlayerView,
        item: MediaItem
    ) {
        super.sendMediaItemToExoPlayer(position, playerView, item)
        Log.d("PUI", "setting player to view")
        playerView.player = lmExoplayer.exoplayer
        lmExoplayer.setMediaItem(position, item)
    }

    override fun playPauseOnVideo(position: Int) {
        super.playPauseOnVideo(position)
        val post = getPostFromAdapter(position)
        val attachment = post.attachments.first()
        when (attachment.mediaActions) {
            MEDIA_ACTION_PLAY -> {
                Log.d("PUI", "state play")
                lmExoplayer.pause()
                val newAttachments = attachment.toBuilder()
                    .mediaActions(MEDIA_ACTION_PAUSE)
                    .build()
                val newPost = post.toBuilder()
                    .attachments(listOf(newAttachments))
                    .fromVideoAction(true)
                    .build()
                mPostAdapter.update(position, newPost)
            }
            MEDIA_ACTION_NONE -> {
                Log.d("PUI", "state none")
                val newAttachments = attachment.toBuilder()
                    .mediaActions(MEDIA_ACTION_PLAY)
                    .build()
                val newPost = post.toBuilder()
                    .attachments(listOf(newAttachments))
                    .fromVideoAction(true)
                    .build()
                Log.d("PUI", "play")
                lmExoplayer.play()
                Log.d("PUI", "update rv")
                mPostAdapter.update(position, newPost)
            }
            MEDIA_ACTION_PAUSE -> {
                Log.d("PUI", "state pause")
                val newAttachments = attachment.toBuilder()
                    .mediaActions(MEDIA_ACTION_PLAY)
                    .build()
                val newPost = post.toBuilder()
                    .attachments(listOf(newAttachments))
                    .fromVideoAction(true)
                    .build()
                mPostAdapter.update(position, newPost)
                lmExoplayer.play()
            }
        }
    }

    override fun onMultipleDocumentsExpanded(postData: PostViewData, position: Int) {
        if (position == mPostAdapter.items().size - 1) {
            binding.recyclerView.post {
                scrollToPositionWithOffset(position)
            }
        }

        mPostAdapter.update(
            position,
            postData.toBuilder()
                .isExpanded(true)
                .fromPostSaved(false)
                .fromPostLiked(false)
                .fromVideoAction(false)
                .build()
        )
    }


    /**
     * Adapter Util Block
     **/

    //get index and post from the adapter using postId
    private fun getIndexAndPostFromAdapter(postId: String): Pair<Int, PostViewData> {
        val index = mPostAdapter.items().indexOfFirst {
            (it is PostViewData) && (it.id == postId)
        }

        val post = getPostFromAdapter(index)

        return Pair(index, post)
    }

    //get post from the adapter using index
    private fun getPostFromAdapter(position: Int): PostViewData {
        return mPostAdapter.items()[position] as PostViewData
    }

    /**
     * Scroll to a position with offset from the top header
     * @param position Index of the item to scroll to
     */
    private fun scrollToPositionWithOffset(position: Int) {
        val px = (ViewUtils.dpToPx(75) * 1.5).toInt()
        (binding.recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            position,
            px
        )
    }
}