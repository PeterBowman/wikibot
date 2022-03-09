$( function () {
    var $content, $results, $summaryLimit, $summaryStart, $summaryEnd, $timestamp, $total, $paginators,
        $container = $( '#mw-content-text' ),
        URL = 'eswikt-lonely-pages/api',
        TIMEOUT = 5000,
        currentLimit = lonelyPages.limit,
        currentOffset = lonelyPages.offset,
        disableClicks = false;

    function queryNodes( $el ) {
        $content = $el.find( '#lonely-pages-content' );
        $results = $el.find( '#lonely-pages-results' );
        $summaryLimit = $el.find( '#lonely-pages-limit' );
        $summaryStart = $el.find( '#lonely-pages-start' );
        $summaryEnd = $el.find( '#lonely-pages-end' );
        $timestamp = $el.find( '#lonely-pages-timestamp' );
        $total = $el.find( '#lonely-pages-total' );
        $paginators = $el.find( '.paginator' );
    }

    function makeRequest( params ) {
        return $.ajax( {
            dataType: 'json',
            url: URL + params,
            timeout: TIMEOUT
        } );
    }

    function readPaginatorData( $el ) {
        switch ( $el.parent().attr( 'class' ) ) {
            case 'paginator-prev':
                currentOffset -= currentLimit;
                break;
            case 'paginator-next':
                currentOffset += currentLimit;
                break;
            case 'paginator-limits':
                currentLimit = $el.text();
                break;
        }

        currentOffset = Math.max( currentOffset, 0 );
        currentLimit = Math.max( currentLimit, 0 );
    }

    function handlePaginators( data, $el ) {
        $paginators.find( '.paginator-prev-value, .paginator-next-value' ).text( currentLimit );

        $paginators.find( '.paginator-prev, .paginator-next' ).each( function () {
            var $this = $( this ),
                $a = $this.find( 'a' ),
                isNext = $this.hasClass( 'paginator-next' );

            if ( !isNext && currentOffset === 0 || isNext && currentOffset + currentLimit > data.total ) {
                $a.replaceWith( $a.html() );
            } else {
                if ( !$a.length ) {
                    $a = $( '<a>' )
                        .html( $this.html() )
                        .appendTo( $this.empty() );
                }

                $a.attr( 'href', '?' + $.param( {
                    offset: currentOffset + currentLimit * ( isNext ? 1 : -1 ),
                    limit: currentLimit
                } ) );
            }
        } );

        $paginators.find( '.paginator-limits > a' ).each( function () {
            var $this = $( this );

            $this.attr( 'href', '?' + $.param( {
                offset: currentOffset,
                limit: $this.text()
            } ) );
        } );
    }

    function updateResults( data ) {
        var arr = $.map( data.results, function ( item ) {
            return '<li><a class="wikilink" target="_blank" title="' + item +
                '" href="https://es.wiktionary.org/wiki/' + encodeURI( item ) +
                '" data-target="' + item + '">' + item + '</a></li>';
        } );

        $timestamp.text( data.timestamp );
        $total.text( data.total );

        $summaryLimit.text( currentLimit );
        $summaryStart.text( currentOffset + 1 );
        $summaryEnd.text( Math.min( currentOffset + currentLimit, data.total ) );

        data.results.length > lonelyPages.columnThreshold
            ? $results.addClass( 'column-list' )
            : $results.removeClass( 'column-list' );

        $results.html( arr.join( '' ) ).attr( 'start', currentOffset + 1 );
    }

    function serializeData() {
        return {
            results: $.map( $results.find( 'a' ), function ( link ) {
                return $( link ).text();
            } ),
            total: $total.text(),
            timestamp: $timestamp.text()
        };
    }

    function pushHistoryState( url, data ) {
        var paginatorValues;

        if ( !window.history ) {
            return;
        }

        paginatorValues = {
            offset: currentOffset,
            limit: currentLimit
        };

        history[url !== undefined ? 'pushState' : 'replaceState']( $.extend( {
            data: data || serializeData()
        }, paginatorValues ), '', location.origin + location.pathname + (
            url || location.search || '?' + $.param( paginatorValues )
        ) );
    }

    $container.on( 'click', '.paginator a', function ( evt ) {
        var $this = $( this ),
            href = $this.attr( 'href' );

        if ( disableClicks ) {
            return false;
        }

        evt.preventDefault();

        $content.addClass( 'content-loading' );
        disableClicks = true;

        return makeRequest( href ).then( function ( data ) {
            readPaginatorData( $this );
            handlePaginators( data );
            updateResults( data );
            $content.removeClass( 'content-loading' );
            disableClicks = false;
            pushHistoryState( href, data );
        },  function ( jqXHR, textStatus, errorThrown ) {
            location.search = href;
        } );
    } );

    window.onpopstate = function ( evt ) {
        if ( evt.state ) {
            currentOffset = evt.state.offset;
            currentLimit = evt.state.limit;
            handlePaginators( evt.state.data );
            updateResults( evt.state.data );
            queryNodes( $container );
        }
    };

    queryNodes( $container );
    pushHistoryState();
} );
