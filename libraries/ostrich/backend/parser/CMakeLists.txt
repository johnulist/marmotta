include_directories(.. ${CMAKE_CURRENT_BINARY_DIR}/..)

add_library(marmotta_parser rdf_parser.h rdf_parser.cc)
target_link_libraries(
        marmotta_parser marmotta_model marmotta_raptor_util
        ${CMAKE_THREAD_LIBS_INIT} ${RAPTOR_LIBRARY} ${GLOG_LIBRARY})