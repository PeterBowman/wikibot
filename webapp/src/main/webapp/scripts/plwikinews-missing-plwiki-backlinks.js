$( function () {
	var $content, $results, $summaryLimit, $summaryStart, $summaryEnd, $total, $paginators,
		$container = $( '#mw-content-text' ),
		URL = 'plwikinews-missing-plwiki-backlinks/api',
		TIMEOUT = 5000,
		currentLimit = plwikinewsMissingPlwikiBacklinks.limit,
		currentOffset = plwikinewsMissingPlwikiBacklinks.offset,
		disableClicks = false,
		initialRequest = true;
	
	function queryNodes( $el ) {
		$content = $el.find( '#plwikinews-missing-plwiki-backlinks-content' );
		$results = $el.find( '#plwikinews-missing-plwiki-backlinks-results' );
		$summaryLimit = $el.find( '#plwikinews-missing-plwiki-backlinks-limit' );
		$summaryStart = $el.find( '#plwikinews-missing-plwiki-backlinks-start' );
		$summaryEnd = $el.find( '#plwikinews-missing-plwiki-backlinks-end' );
		$total = $el.find( '#plwikinews-missing-plwiki-backlinks-total' );
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
	
	function handlePaginators( data ) {
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
		$total.text( data.total );
		$summaryLimit.text( currentLimit );
		$summaryStart.text( currentOffset + 1 );
		$summaryEnd.text( Math.min( currentOffset + currentLimit, data.total ) );
		
		data.results.length > plwikinewsMissingPlwikiBacklinks.columnThreshold
			? $results.addClass( 'column-list' )
			: $results.removeClass( 'column-list' );
		
		$results.html( $.map( data.results, function ( item ) {
			var out = '<a class="wikilink" target="_blank" title="' + item +
				'" href="https://pl.wikinews.org/wiki/' + encodeURI( item ) +
				'" data-target="' + item + '" data-href="https://pl.wikinews.org/"' +
				'>' + item.replaceAll( '_', ' ' ) + '</a>';
			
			return '<li>' + out + '</li>';
		} ).join( '' ) ).attr( 'start', currentOffset + 1 );
	}
	
	function pushHistoryState( data, url ) {
		var searchValues;
		
		if ( !window.history ) {
			return;
		}
		
		searchValues = {
			offset: currentOffset,
			limit: currentLimit
		};
		
		history[url !== undefined ? 'pushState' : 'replaceState']( $.extend( {
			data: data
		}, searchValues ), '', location.origin + location.pathname + (
			url || location.search || '?' + $.param( searchValues )
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
		
		return $.when(
			makeRequest( href ),
			initialRequest ? makeRequest( location.search ) : null
		).then( function ( data, initial ) {
			if ( initialRequest ) {
				pushHistoryState( initial[ 0 ] );
				initialRequest = false;
			}
			
			readPaginatorData( $this );
			
			handlePaginators( data[ 0 ] );
			updateResults( data[ 0 ] );
			$content.removeClass( 'content-loading' );
			disableClicks = false;
			pushHistoryState( data[ 0 ], href );
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
} );
